package tz.co.nlolo.lifeplatform.communication.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The messages a customer expected and never got (2026-11-08, the user): a policy lapsed or reinstated; a claim
 * received, approved, declined or paid; a surrender, a loan or a one-off benefit paid.
 *
 * <p>Who is told comes from {@link Recipients}: these events mostly name only a policy, a claim or a loan. A claim's
 * messages go to its CLAIMANT -- on a death claim the policyholder may be the one who died -- and fall back to the
 * holder. A recurring income or annuity instalment is not announced: a text a month says nothing new.
 */
@Component("communicationCustomerNoticeListener")
class CustomerNoticeListener {

    private static final Logger log = LoggerFactory.getLogger(CustomerNoticeListener.class);

    /** One-off benefits worth a message; INCOME and ANNUITY instalments recur and are not announced. */
    private static final Set<String> ANNOUNCED_BENEFITS = Set.of("SURVIVAL", "MATURITY", "RETURN_OF_PREMIUM", "COMMUTATION");

    private final NotificationApi notifications;
    private final Recipients recipients;
    private final TransactionTemplate requiresNew;

    CustomerNoticeListener(NotificationApi notifications, Recipients recipients, PlatformTransactionManager transactionManager) {
        this.notifications = notifications;
        this.recipients = recipients;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            // Remembering who is who.
            case "policy.PolicyIssued", "policy.PolicyActivated", "policy.PolicyholderChanged" -> handle(envelope, (t, p) ->
                recipients.remember(t, "POLICY", str(p.get("policyNumber")), uuid(p.get("policyholderPartyId")), str(p.get("policyNumber"))));
            case "policyloan.LoanOriginated" -> handle(envelope, (t, p) ->
                recipients.remember(t, "LOAN", str(p.get("loanId")), null, str(p.get("policyNumber"))));
            case "claims.ClaimRegistered" -> handle(envelope, (t, p) -> {
                recipients.remember(t, "CLAIM", str(p.get("claimId")), uuid(p.get("claimantPartyId")), str(p.get("policyNumber")));
                toClaimant(envelope, t, p, "CLAIM_RECEIVED", null);
            });
            // Telling them.
            case "policy.PolicyLapsed" -> handle(envelope, (t, p) -> {
                // A unit-linked fund that ran out has its own message (UNIT_LINKED_LAPSED_EXHAUSTED).
                if (p.get("exhaustedOn") == null) toHolder(envelope, t, str(p.get("policyNumber")), "POLICY_LAPSED", null);
            });
            case "policy.PolicyReinstated" -> handle(envelope, (t, p) ->
                toHolder(envelope, t, str(p.get("policyNumber")), "POLICY_REINSTATED", null));
            case "claims.ClaimApproved" -> handle(envelope, (t, p) -> toClaimant(envelope, t, p, "CLAIM_APPROVED", p.get("approvedAmount")));
            case "claims.ClaimRejected" -> handle(envelope, (t, p) -> toClaimant(envelope, t, p, "CLAIM_DECLINED", null));
            case "claims.ClaimSettled" -> handle(envelope, (t, p) ->
                toClaimant(envelope, t, p, "CLAIM_PAID", p.get("paidAmount") != null ? p.get("paidAmount") : p.get("settledAmount")));
            case "policy.SurrenderPaid" -> handle(envelope, (t, p) ->
                toHolder(envelope, t, str(p.get("policyNumber")), "SURRENDER_PAID", p.get("paidAmount")));
            case "policyloan.LoanDisbursed" -> handle(envelope, (t, p) -> recipients.find(t, "LOAN", str(p.get("loanId")))
                .ifPresentOrElse(loan -> toHolder(envelope, t, loan.policyNumber(), "LOAN_PAID_OUT", p.get("amount")),
                    () -> log.warn("Loan {} paid out, but its policy is not known here; nobody told", p.get("loanId"))));
            case "benefitpayout.PayoutPaid" -> handle(envelope, (t, p) -> {
                if (ANNOUNCED_BENEFITS.contains(str(p.get("kind")))) {
                    toHolder(envelope, t, str(p.get("policyNumber")), "BENEFIT_PAID", p.get("paidAmount"));
                }
            });
            default -> { /* not a customer notice */ }
        }
    }

    private void toHolder(DomainEventEnvelope<?> envelope, UUID tenantId, String policyNumber, String key, Object money) {
        Optional<UUID> holder = recipients.holderOf(tenantId, policyNumber);
        if (holder.isEmpty()) {
            log.warn("{} on policy {}: its holder is not known here; nobody told", key, policyNumber);
            return;
        }
        send(envelope, holder.get(), policyNumber, key, money);
    }

    private void toClaimant(DomainEventEnvelope<?> envelope, UUID tenantId, Map<String, Object> p, String key, Object money) {
        String claimId = str(p.get("claimId"));
        Optional<Recipients.Recipient> claim = recipients.find(tenantId, "CLAIM", claimId);
        String policyNumber = str(p.get("policyNumber")) != null ? str(p.get("policyNumber"))
            : claim.map(Recipients.Recipient::policyNumber).orElse(null);
        Optional<UUID> who = claim.map(Recipients.Recipient::partyId)
            .or(() -> Optional.ofNullable(uuid(p.get("claimantPartyId"))))
            .or(() -> recipients.holderOf(tenantId, policyNumber));
        if (who.isEmpty()) {
            log.warn("{} on claim {}: nobody known to tell", key, claimId);
            return;
        }
        send(envelope, who.get(), policyNumber, key, money);
    }

    private void send(DomainEventEnvelope<?> envelope, UUID partyId, String policyNumber, String key, Object money) {
        Map<String, String> values = money == null
            ? Map.of("policyNumber", policyNumber)
            : Map.of("policyNumber", policyNumber, "amount", money(money));
        notifications.notify(envelope.eventId(), partyId, policyNumber, key, values);
    }

    private void handle(DomainEventEnvelope<?> envelope, java.util.function.BiConsumer<UUID, Map<String, Object>> handler) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            requiresNew.executeWithoutResult(status -> handler.accept(envelope.tenantId(), payload));
        } catch (RuntimeException e) {
            // A message missed is recorded nowhere else: say so plainly, and never undo what already happened.
            log.error("communication could not act on {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }

    /** "TZS 50,000.00" -- grouped, because a customer reads this on a phone; never the JVM's own locale. */
    private static String money(Object moneyPayload) {
        if (!(moneyPayload instanceof Map<?, ?> m) || m.get("amount") == null) return "";
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return m.get("currencyCode") + " " + format.format(new BigDecimal(String.valueOf(m.get("amount"))));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static UUID uuid(Object o) {
        if (o == null) return null;
        return o instanceof UUID u ? u : UUID.fromString(String.valueOf(o));
    }
}
