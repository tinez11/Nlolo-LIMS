package tz.co.nlolo.lifeplatform.policy.domain;

import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit assertions for the date-bounded on-risk answer and the expiry transition (product
 * step 0, D1/D2). No Spring, no database -- the aggregate's own logic, read directly, the same
 * arrangement {@link tz.co.nlolo.lifeplatform.policy.PolicyClaimClosureTest} uses for closure.
 */
class PolicyOnRiskAndExpiryTest {

    private static final LocalDate TODAY = LocalDate.now();

    private static Policy policy(String category) {
        return new Policy("POL-STEP0-01", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), category, null, new BigDecimal("1000000"), "TZS",
            new BigDecimal("50000.00"), "TZS", "MONTHLY", null, "test-staff");
    }

    /** A live term policy: commenced a year ago, matures in a year. */
    private static Policy activeTermPolicy() {
        Policy p = policy("TERM_LIFE");
        p.recordIssuedOn(TODAY.minusYears(1));
        p.applyTerm(TODAY.minusYears(1), 24, null); // matures in ~1 year
        p.activate();
        return p;
    }

    @Test
    void aProposedPolicyIsNeverOnRisk() {
        Policy p = policy("TERM_LIFE");
        p.recordIssuedOn(TODAY);
        assertFalse(p.wasOnRiskOn(TODAY));
    }

    @Test
    void nullDayIsNeverOnRisk() {
        assertFalse(activeTermPolicy().wasOnRiskOn(null));
    }

    @Test
    void anActivePolicyWithNoTermIsOnRiskOnAnyDayFromCommencement() {
        Policy p = policy("TERM_LIFE");
        p.recordIssuedOn(TODAY.minusMonths(2));
        p.applyTerm(TODAY.minusMonths(2), null, null); // no term -> no maturity
        p.activate();
        assertTrue(p.wasOnRiskOn(TODAY));
        assertTrue(p.wasOnRiskOn(TODAY.minusMonths(1)));
        // Before the recorded commencement, not on risk.
        assertFalse(p.wasOnRiskOn(TODAY.minusMonths(3)));
    }

    @Test
    void withNoCommencementRecordedCoverStartsAtTheIssueDate() {
        // Audit 2026-10-07: this asserted "no lower bound", which let a death claim dated five years before
        // the policy existed through the on-risk check. No contract covers a day before it was issued.
        Policy p = policy("TERM_LIFE");
        p.recordIssuedOn(TODAY.minusDays(10));
        p.activate();
        assertFalse(p.wasOnRiskOn(TODAY.minusYears(5)));
        assertFalse(p.wasOnRiskOn(TODAY.minusDays(11)));
        assertTrue(p.wasOnRiskOn(TODAY.minusDays(10)));
        assertTrue(p.wasOnRiskOn(TODAY));
    }

    @Test
    void coverEndsAsTheMaturityDateBegins() {
        Policy p = policy("TERM_LIFE");
        p.recordIssuedOn(TODAY.minusYears(2));
        p.applyTerm(TODAY.minusYears(2), 12, null); // matured a year ago
        p.activate();
        LocalDate maturity = TODAY.minusYears(1);
        assertTrue(p.wasOnRiskOn(maturity.minusDays(1)));
        assertFalse(p.wasOnRiskOn(maturity));
        assertFalse(p.wasOnRiskOn(maturity.plusDays(1)));
    }

    @Test
    void aLapsedPolicyWasOnRiskUntilTheDayItLapsed() {
        Policy p = activeTermPolicy();
        p.lapse();
        // lapsedAt is stamped now; a day before today is before the lapse.
        assertTrue(p.wasOnRiskOn(TODAY.minusDays(1)));
        // Today is the lapse day -- no longer on risk.
        assertFalse(p.wasOnRiskOn(TODAY));
    }

    @Test
    void expiredCountsAsOnRiskForTheWholeTerm() {
        Policy p = policy("TERM_LIFE");
        p.recordIssuedOn(TODAY.minusYears(2));
        p.applyTerm(TODAY.minusYears(2), 12, null); // matured a year ago
        p.activate();
        p.expire(TODAY);
        assertEquals("EXPIRED", p.getStatus());
        assertTrue(p.wasOnRiskOn(TODAY.minusMonths(18))); // within the term
    }

    @Test
    void expireRequiresAPassedMaturityDate() {
        Policy p = policy("TERM_LIFE");
        p.recordIssuedOn(TODAY);
        p.applyTerm(TODAY, 24, null); // matures in the future
        p.activate();
        assertFalse(p.canExpire(TODAY));
        assertThrows(InvalidPolicyStateException.class, () -> p.expire(TODAY));
    }

    @Test
    void expireIsIdempotent() {
        Policy p = policy("TERM_LIFE");
        p.recordIssuedOn(TODAY.minusYears(2));
        p.applyTerm(TODAY.minusYears(2), 12, null);
        p.activate();
        p.expire(TODAY);
        p.expire(TODAY); // no throw, still EXPIRED
        assertEquals("EXPIRED", p.getStatus());
        assertTrue(p.isClosed());
    }

    @Test
    void aClosedPolicyIsNeverReopenedByExpiry() {
        Policy p = policy("TERM_LIFE");
        p.recordIssuedOn(TODAY.minusYears(2));
        p.applyTerm(TODAY.minusYears(2), 12, null);
        p.activate();
        p.mature(); // MATURED, a settled maturity claim
        p.expire(TODAY); // alreadyClosed -> silent no-op
        assertEquals("MATURED", p.getStatus());
    }

    @Test
    void premiumPayingUntilIsThePayingTermFromCommencement() {
        Policy p = policy("TERM_LIFE");
        p.recordIssuedOn(TODAY);
        p.applyTerm(TODAY, 24, 12); // 24-month cover, pay for 12
        assertEquals(TODAY.plusMonths(12), p.premiumPayingUntil());
    }

    @Test
    void premiumPayingUntilFallsBackToThePolicyTerm() {
        Policy p = policy("TERM_LIFE");
        p.recordIssuedOn(TODAY);
        p.applyTerm(TODAY, 12, null); // no separate paying term
        assertEquals(TODAY.plusMonths(12), p.premiumPayingUntil());
    }

    @Test
    void premiumPayingUntilIsNullWhenTheContractDoesNotTerm() {
        Policy p = policy("TERM_LIFE");
        p.recordIssuedOn(TODAY);
        p.applyTerm(TODAY, null, null); // whole life / renewable
        assertNull(p.premiumPayingUntil());
    }
}
