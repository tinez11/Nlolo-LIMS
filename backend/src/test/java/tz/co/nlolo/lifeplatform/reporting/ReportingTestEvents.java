package tz.co.nlolo.lifeplatform.reporting;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reporting.application.EventApplier;
import tz.co.nlolo.lifeplatform.reporting.domain.CivilDates;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ProjectionStore;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Seeds the read model with exactly the events a report test needs, through the real EventApplier -- the
 * payload shapes are the producers' (see ReportingEventHandlers' javadoc), dated where the test says. */
@TestComponent
public class ReportingTestEvents {

    public record Sale(String policyNumber, UUID productId, String category, String frequency, String premium,
                       String sumAssured, LocalDate activatedOn, UUID agentId, String channel, String branch,
                       UUID holderPartyId, String portfolio, boolean groupScheme, int lives) {
        public static Sale of(String policyNumber, UUID productId, LocalDate activatedOn) {
            return new Sale(policyNumber, productId, "TERM_LIFE", "MONTHLY", "10000.00", "1000000.00", activatedOn,
                null, "AGENCY", "DSM-01", null, "TRM", false, 1);
        }

        public Sale premium(String newFrequency, String amount) {
            return new Sale(policyNumber, productId, category, newFrequency, amount, sumAssured, activatedOn, agentId,
                channel, branch, holderPartyId, portfolio, groupScheme, lives);
        }

        public Sale agent(UUID agent) {
            return new Sale(policyNumber, productId, category, frequency, premium, sumAssured, activatedOn, agent,
                channel, branch, holderPartyId, portfolio, groupScheme, lives);
        }

        public Sale holder(UUID holder) {
            return new Sale(policyNumber, productId, category, frequency, premium, sumAssured, activatedOn, agentId,
                channel, branch, holder, portfolio, groupScheme, lives);
        }

        public Sale portfolio(String code) {
            return new Sale(policyNumber, productId, category, frequency, premium, sumAssured, activatedOn, agentId,
                channel, branch, holderPartyId, code, groupScheme, lives);
        }

        public Sale scheme(int livesAtActivation) {
            return new Sale(policyNumber, productId, "GROUP_LIFE", frequency, premium, sumAssured, activatedOn, agentId,
                channel, branch, holderPartyId, portfolio, true, livesAtActivation);
        }
    }

    private final EventApplier applier;
    private final ProjectionStore store;
    private final TransactionTemplate tx;

    public ReportingTestEvents(EventApplier applier, ProjectionStore store, PlatformTransactionManager transactionManager) {
        this.applier = applier;
        this.store = store;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public static Instant at(LocalDate day) {
        return day.atTime(9, 0).atZone(CivilDates.ZONE).toInstant();
    }

    private void apply(UUID tenant, String type, LocalDate day, Map<String, Object> payload) {
        EventApplier.Outcome outcome = applier.apply(tenant, UUID.randomUUID(), type, at(day), payload);
        if (outcome != EventApplier.Outcome.APPLIED) {
            throw new IllegalStateException(type + " was not applied: " + outcome);
        }
    }

    private static Map<String, Object> policyPayload(Sale s) {
        Map<String, Object> p = new HashMap<>();
        p.put("policyNumber", s.policyNumber());
        p.put("policyholderPartyId", s.holderPartyId());
        p.put("productId", s.productId());
        p.put("productVersionId", UUID.randomUUID());
        p.put("sumAssured", Map.of("amount", s.sumAssured(), "currencyCode", "TZS"));
        p.put("premium", Map.of("amount", s.premium(), "currencyCode", "TZS"));
        p.put("premiumFrequency", s.frequency());
        p.put("issueDate", s.activatedOn().toString());
        p.put("agentOfRecordId", s.agentId());
        p.put("portfolioCode", s.portfolio());
        return p;
    }

    public void sell(UUID tenant, Sale s) {
        Map<String, Object> issued = policyPayload(s);
        issued.put("status", "PROPOSED");
        issued.put("salesChannel", s.channel());
        issued.put("branchCode", s.branch());
        apply(tenant, "policy.PolicyIssued", s.activatedOn(), issued);
        Map<String, Object> activated = policyPayload(s);
        activated.put("activatedAt", s.activatedOn().toString());
        activated.put("productCategory", s.category());
        activated.put("groupScheme", s.groupScheme());
        activated.put("livesCovered", s.lives());
        apply(tenant, "policy.PolicyActivated", s.activatedOn(), activated);
    }

    public void offerNotTakenUp(UUID tenant, Sale s, LocalDate expiredOn) {
        Map<String, Object> issued = policyPayload(s);
        issued.put("status", "PROPOSED");
        apply(tenant, "policy.PolicyIssued", s.activatedOn(), issued);
        apply(tenant, "policy.PolicyNotTakenUp", expiredOn, Map.of("policyNumber", s.policyNumber(),
            "productId", s.productId(), "expiredAt", expiredOn.toString()));
    }

    public void lapse(UUID tenant, String policyNumber, LocalDate on) {
        apply(tenant, "policy.PolicyLapsed", on, Map.of("policyNumber", policyNumber, "lapsedAt", at(on).toString()));
    }

    public void reinstate(UUID tenant, String policyNumber, LocalDate on) {
        apply(tenant, "policy.PolicyReinstated", on, Map.of("policyNumber", policyNumber, "reinstatedAt", at(on).toString()));
    }

    public void suspend(UUID tenant, String policyNumber, LocalDate on) {
        apply(tenant, "policy.PolicySuspended", on, Map.of("policyNumber", policyNumber, "suspendedAt", at(on).toString(),
            "reason", "test"));
    }

    public void paidUp(UUID tenant, String policyNumber, LocalDate on) {
        apply(tenant, "policy.PolicyMadePaidUp", on, Map.of("policyNumber", policyNumber, "madePaidUpAt", at(on).toString(),
            "paidUpSumAssured", Map.of("amount", "500000.00", "currencyCode", "TZS"),
            "originalSumAssured", Map.of("amount", "1000000.00", "currencyCode", "TZS")));
    }

    public void cancelFreeLook(UUID tenant, String policyNumber, LocalDate on) {
        apply(tenant, "policy.PolicyCancelledFreeLook", on, Map.of("policyNumber", policyNumber,
            "cancelledAt", at(on).toString(), "cancelledBy", "approver"));
    }

    public void collect(UUID tenant, String policyNumber, String amount, LocalDate on, boolean mobileMoney) {
        Map<String, Object> p = new HashMap<>();
        p.put("invoiceId", UUID.randomUUID());
        p.put("policyNumber", policyNumber);
        p.put("amount", Map.of("amount", amount, "currencyCode", "TZS"));
        p.put("collectedAt", at(on).toString());
        if (mobileMoney) p.put("payerRef", "255712000111");
        apply(tenant, "billing.PremiumCollected", on, p);
    }

    public void members(UUID tenant, String policyNumber, String kind, int livesAfter, String totalAfter, LocalDate on) {
        boolean joined = "JOINED".equals(kind);
        Map<String, Object> p = new HashMap<>();
        p.put("policyNumber", policyNumber);
        p.put(joined ? "joinedOn" : "leftOn", on.toString());
        p.put("livesCovered", livesAfter);
        p.put("schemeTotalCovered", Map.of("amount", totalAfter, "currencyCode", "TZS"));
        if (!joined) {
            p.put("policyMemberId", UUID.randomUUID());
            p.put("reason", "REPAID");
        }
        apply(tenant, joined ? "policy.GroupMemberAdded" : "policy.GroupMemberExited", on, p);
    }

    /** Clients are written straight to the store: the party module is not what a report test is about. */
    public void client(UUID tenant, UUID partyId, String type, String kyc, String name, LocalDate registeredOn,
                       LocalDate dateOfBirth, String sex) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenant);
        try {
            tx.executeWithoutResult(s -> store.upsertClient(tenant, new ProjectionStore.ClientFacts(partyId, type, kyc,
                name, registeredOn, dateOfBirth, sex)));
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
