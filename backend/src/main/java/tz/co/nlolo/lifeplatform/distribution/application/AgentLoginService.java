package tz.co.nlolo.lifeplatform.distribution.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.distribution.api.AgentLoginIssued;
import tz.co.nlolo.lifeplatform.distribution.api.AgentLoginView;
import tz.co.nlolo.lifeplatform.distribution.api.AgentView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.distribution.api.InvalidAgentStateException;
import tz.co.nlolo.lifeplatform.distribution.api.LicenseStatus;
import tz.co.nlolo.lifeplatform.distribution.domain.AgentLogin;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.AgentLoginRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Giving an agent a login to the agent console (2026-10-28, user decision: from the agent's page, as a client is given
 * portal access). Until now an agent onboarded in the register could never sign in.
 *
 * <p>The platform creates the login and writes the tenant and the agent's party onto it, so the party the token claims
 * is the agent's own record. With an email the identity provider sends its own set-your-password link; otherwise a
 * one-time password goes to the agent's phone by text, kept out of the message log; only with neither, or when the text
 * fails, is it shown once to the staff member to hand over. The agent changes it at first sign-in either way.
 *
 * <p>The login follows the agent: suspending an agent closes it and signs them out; reactivating opens it again --
 * unless staff had closed it themselves.
 */
@Service
public class AgentLoginService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoginService.class);
    /** No 0/O, 1/l/I: read aloud over a counter or a phone. The portal invite's alphabet. */
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    static final String MASK = "••••••••";

    private final AgentLoginRepository logins;
    private final AgentLogins accounts;
    private final DistributionApi agents;
    private final PartyApi parties;
    private final NotificationApi notifications;
    private final boolean emailEnabled;
    private final String consoleUrl;
    private final TransactionTemplate requiresNew;

    public AgentLoginService(AgentLoginRepository logins, AgentLogins accounts, DistributionApi agents, PartyApi parties,
                             NotificationApi notifications, PlatformTransactionManager transactions,
                             @Value("${communication.email.enabled:true}") boolean emailEnabled,
                             @Value("${app.agent-logins.console-url}") String consoleUrl) {
        this.logins = logins;
        this.accounts = accounts;
        this.agents = agents;
        this.parties = parties;
        this.notifications = notifications;
        this.emailEnabled = emailEnabled;
        this.consoleUrl = consoleUrl;
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(readOnly = true)
    public AgentLoginView login(UUID agentId) {
        agents.getAgent(agentId); // a 404 for an agent that does not exist, not an empty login
        return logins.findByTenantIdAndAgentId(TenantContext.get(), agentId).map(AgentLoginService::view)
            .orElseGet(() -> AgentLoginView.none(agentId));
    }

    /** Creates the agent's login, or opens a closed one again, and sends the first password. */
    @Transactional
    public AgentLoginIssued give(UUID agentId, String by) {
        UUID tenantId = TenantContext.get();
        AgentView agent = agents.getAgent(agentId);
        if (agent.licenseStatus() != LicenseStatus.ACTIVE) {
            throw new InvalidAgentStateException("This agent is " + agent.licenseStatus().name().toLowerCase(Locale.ROOT)
                + ": only an active agent can be given a login");
        }
        PartyDetailView party = parties.getPartyDetail(agent.partyId());
        String email = blankToNull(party.email());
        String phone = blankToNull(party.phoneNumber());
        if (email == null && phone == null) {
            throw new InvalidAgentStateException(party.displayName() + " has no email address or phone number on file:"
                + " record one on their client record first, so the login has a username and a way to reach them");
        }
        AgentLogin.Delivery delivery = deliveryFor(email, phone, emailEnabled);

        AgentLogin login = logins.findByTenantIdAndAgentId(tenantId, agentId).orElse(null);
        if (login != null && login.getStatus() == AgentLogin.Status.OPEN) {
            throw new InvalidAgentStateException(party.displayName() + " already has a login: send a new password"
                + " instead, or close it first");
        }
        if (login == null) {
            String username = email != null ? email.toLowerCase(Locale.ROOT) : phone;
            String userId = accounts.create(username, email, party.displayName(), tenantId, agent.partyId());
            login = new AgentLogin(tenantId, agentId, agent.partyId(), userId, username, delivery, by);
        } else {
            accounts.setEnabled(login.getKeycloakUserId(), true);
            login.opened();
            login.passwordSent(delivery);
        }
        logins.save(login);
        return firstPassword(login, party, delivery);
    }

    /** A new password for an open login: the first one was lost, or the agent forgot theirs and has no email. */
    @Transactional
    public AgentLoginIssued resend(UUID agentId) {
        AgentLogin login = existing(agentId);
        if (login.getStatus() != AgentLogin.Status.OPEN) {
            throw new InvalidAgentStateException("This agent's login is closed: open it again instead");
        }
        PartyDetailView party = parties.getPartyDetail(login.getPartyId());
        AgentLogin.Delivery delivery = deliveryFor(blankToNull(party.email()), blankToNull(party.phoneNumber()),
            emailEnabled);
        login.passwordSent(delivery);
        logins.save(login);
        return firstPassword(login, party, delivery);
    }

    /** Closes the login and signs the agent out everywhere. */
    @Transactional
    public AgentLoginView close(UUID agentId, String by) {
        AgentLogin login = existing(agentId);
        if (login.getStatus() == AgentLogin.Status.OPEN) {
            accounts.setEnabled(login.getKeycloakUserId(), false);
            login.closed(AgentLogin.Status.CLOSED, by);
            logins.save(login);
        }
        return view(login);
    }

    /** Suspending an agent closes their login; reactivating opens one the suspension closed. After the agent commits. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAgentStatus(DomainEventEnvelope<?> envelope) {
        boolean suspended = "distribution.AgentSuspended".equals(envelope.eventType());
        boolean reactivated = "distribution.AgentReactivated".equals(envelope.eventType());
        if (!suspended && !reactivated) return;
        Object agentId = envelope.payload() instanceof Map<?, ?> payload ? payload.get("agentId") : null;
        if (agentId == null) return;
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            requiresNew.executeWithoutResult(status -> logins
                .findByTenantIdAndAgentId(envelope.tenantId(), UUID.fromString(agentId.toString()))
                .ifPresent(login -> {
                    if (suspended && login.getStatus() == AgentLogin.Status.OPEN) {
                        accounts.setEnabled(login.getKeycloakUserId(), false);
                        login.closed(AgentLogin.Status.SUSPENDED, "system");
                        logins.save(login);
                    } else if (reactivated && login.getStatus() == AgentLogin.Status.SUSPENDED) {
                        accounts.setEnabled(login.getKeycloakUserId(), true);
                        login.opened();
                        logins.save(login);
                    }
                }));
        } catch (RuntimeException e) {
            // The agent's own change stands; the login is left as it was and the reason is logged for someone to act on.
            log.error("Agent {}'s login was not {} after the agent was: {}", agentId,
                suspended ? "closed" : "reopened", e.toString());
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }

    /** The portal's routing: the identity provider's link where there is email, a texted password, or a handover. */
    static AgentLogin.Delivery deliveryFor(String email, String phone, boolean emailEnabled) {
        if (email != null && emailEnabled) return AgentLogin.Delivery.EMAIL_LINK;
        return phone != null ? AgentLogin.Delivery.SMS_PASSWORD : AgentLogin.Delivery.TEMPORARY_PASSWORD;
    }

    private AgentLoginIssued firstPassword(AgentLogin login, PartyDetailView party, AgentLogin.Delivery delivery) {
        String userId = login.getKeycloakUserId();
        if (delivery == AgentLogin.Delivery.EMAIL_LINK) {
            accounts.sendSetPasswordLink(userId);
            return new AgentLoginIssued(view(login), null, null, null);
        }
        String password = temporaryPassword();
        accounts.setTemporaryPassword(userId, password);
        if (delivery == AgentLogin.Delivery.TEMPORARY_PASSWORD) {
            return new AgentLoginIssued(view(login), password, null, null);
        }
        String phone = blankToNull(party.phoneNumber());
        String failure = phone == null ? "no phone number on file"
            : notifications.sendUnloggedSms(login.getPartyId(), phone, "AGENT_LOGIN",
                loginText(login.getUsername(), password), loginText(login.getUsername(), MASK));
        if (failure == null) {
            return new AgentLoginIssued(view(login), null, phone, null);
        }
        // Never a dead end on a gateway: the login exists, so the staff member hands the password over.
        return new AgentLoginIssued(view(login), password, null,
            "The text was not sent (" + failure + "). Give the agent this password yourself.");
    }

    private String loginText(String username, String password) {
        return "Your agent login: username " + username + ", one-time password " + password + ". Sign in at "
            + consoleUrl + " - you will choose your own password first.";
    }

    private AgentLogin existing(UUID agentId) {
        agents.getAgent(agentId);
        return logins.findByTenantIdAndAgentId(TenantContext.get(), agentId)
            .orElseThrow(() -> new InvalidAgentStateException("This agent has no login yet: give them one first"));
    }

    static String temporaryPassword() {
        StringBuilder password = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            password.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static AgentLoginView view(AgentLogin l) {
        return new AgentLoginView(l.getAgentId(), l.getStatus().name(), l.getUsername(), l.getDelivery().name(),
            l.getCreatedBy(), l.getCreatedAt(), l.getPasswordSentAt(), l.getClosedBy(), l.getClosedAt());
    }
}
