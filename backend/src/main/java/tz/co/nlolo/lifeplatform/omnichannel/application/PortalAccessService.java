package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.omnichannel.api.PortalAccessRefusedException;
import tz.co.nlolo.lifeplatform.omnichannel.api.PortalAccessView;
import tz.co.nlolo.lifeplatform.omnichannel.api.PortalInvite;
import tz.co.nlolo.lifeplatform.omnichannel.domain.PortalAccess;
import tz.co.nlolo.lifeplatform.omnichannel.infrastructure.PortalAccessRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.party.api.PartyType;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

/**
 * Inviting a client to the customer portal (2026-10-08, the customer portal design step 1; the user's D1: staff invite,
 * no self-registration; D2: policyholders only).
 *
 * <p>The platform creates the login itself and writes the tenant and party onto it, so the party a customer is
 * authorised as is the one staff chose on screen -- never an id somebody typed into Keycloak. With an email the
 * identity provider sends its own set-your-password link; without one, a one-time password is returned to the staff
 * member once and kept nowhere.
 */
@Service
public class PortalAccessService {

    /** No 0/O, 1/l/I: read aloud over a counter or a phone. */
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PortalAccessRepository accesses;
    private final CustomerAccounts accounts;
    private final PartyApi partyApi;
    private final PolicyApi policyApi;

    public PortalAccessService(PortalAccessRepository accesses, CustomerAccounts accounts, PartyApi partyApi,
                               PolicyApi policyApi) {
        this.accesses = accesses;
        this.accounts = accounts;
        this.partyApi = partyApi;
        this.policyApi = policyApi;
    }

    @Transactional(readOnly = true)
    public PortalAccessView access(UUID partyId) {
        return accesses.findByTenantIdAndPartyId(TenantContext.get(), partyId).map(PortalAccessService::view)
            .orElseGet(() -> PortalAccessView.notInvited(partyId));
    }

    @Transactional
    public PortalInvite invite(UUID partyId, String invitedBy) {
        UUID tenantId = TenantContext.get();
        PartyDetailView party = partyApi.getPartyDetail(partyId);
        if (party.partyType() != PartyType.INDIVIDUAL) {
            throw new PortalAccessRefusedException("Only a person can be invited to the customer portal; "
                + party.displayName() + " is " + party.partyType());
        }
        if (policyApi.searchPolicies(partyId, null, null, null, null, PageRequest.of(0, 1)).getTotalElements() == 0) {
            throw new PortalAccessRefusedException(party.displayName() + " holds no policy: the portal shows a"
                + " policyholder their own policies, so there would be nothing to see");
        }
        String email = blankToNull(party.email());
        String phone = blankToNull(party.phoneNumber());
        if (email == null && phone == null) {
            throw new PortalAccessRefusedException(party.displayName() + " has no email address or phone number on"
                + " file: record one first, so the login has a username and a way to reach them");
        }
        PortalAccess.Delivery delivery = email != null ? PortalAccess.Delivery.EMAIL_LINK
            : PortalAccess.Delivery.TEMPORARY_PASSWORD;

        PortalAccess access = accesses.findByTenantIdAndPartyId(tenantId, partyId).orElse(null);
        if (access != null && access.getStatus() != PortalAccess.Status.REVOKED) {
            throw new PortalAccessRefusedException(party.displayName() + " already has portal access ("
                + access.getStatus().name().toLowerCase(Locale.ROOT) + "): re-send the invite, or revoke it first", true);
        }
        if (access == null) {
            String username = email != null ? email.toLowerCase(Locale.ROOT) : phone;
            String userId = accounts.create(username, email, party.displayName(), tenantId, partyId);
            access = new PortalAccess(tenantId, partyId, userId, username, delivery, invitedBy);
        } else {
            accounts.setEnabled(access.getKeycloakUserId(), true);
            access.reinvited(delivery, invitedBy);
        }
        accesses.save(access);
        return new PortalInvite(view(access), deliverFirstPassword(access.getKeycloakUserId(), delivery));
    }

    /** Sends the link again, or issues a new one-time password; a lost first password is the usual reason. */
    @Transactional
    public PortalInvite resend(UUID partyId) {
        PortalAccess access = existing(partyId);
        if (access.getStatus() == PortalAccess.Status.REVOKED) {
            throw new PortalAccessRefusedException("This client's portal access is revoked: invite them again instead");
        }
        return new PortalInvite(view(access), deliverFirstPassword(access.getKeycloakUserId(), access.getDelivery()));
    }

    @Transactional
    public PortalAccessView revoke(UUID partyId, String revokedBy) {
        PortalAccess access = existing(partyId);
        if (access.getStatus() == PortalAccess.Status.REVOKED) {
            return view(access);
        }
        accounts.setEnabled(access.getKeycloakUserId(), false);
        access.revoked(revokedBy);
        return view(accesses.save(access));
    }

    /** The customer's own token reached the platform: their invite is used. Nothing to do for a client never invited. */
    @Transactional
    public void signedIn(UUID partyId) {
        accesses.findByTenantIdAndPartyId(TenantContext.get(), partyId).ifPresent(access -> {
            if (access.getStatus() == PortalAccess.Status.INVITED) {
                access.activated();
                accesses.save(access);
            }
        });
    }

    private String deliverFirstPassword(String userId, PortalAccess.Delivery delivery) {
        if (delivery == PortalAccess.Delivery.EMAIL_LINK) {
            accounts.sendSetPasswordLink(userId);
            return null;
        }
        String password = temporaryPassword();
        accounts.setTemporaryPassword(userId, password);
        return password;
    }

    private PortalAccess existing(UUID partyId) {
        return accesses.findByTenantIdAndPartyId(TenantContext.get(), partyId)
            .orElseThrow(() -> new PortalAccessRefusedException("This client has not been invited to the portal"));
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

    private static PortalAccessView view(PortalAccess a) {
        return new PortalAccessView(a.getPartyId(), a.getStatus().name(), a.getUsername(), a.getDelivery().name(),
            a.getInvitedBy(), a.getInvitedAt(), a.getActivatedAt(), a.getRevokedBy(), a.getRevokedAt());
    }
}
