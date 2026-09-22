package tz.co.nlolo.lifeplatform.policy.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Direct, Spring-free unit tests for PolicyApiImpl.resolveSurrenderChargePercent -- the
 * duration-band JSON parsing and percentage-selection logic that task-2-review.md flagged as
 * having zero positive-path coverage: only the "no schedule configured" trivial branch
 * (PolicyApiIntegrationTest.quoteSurrenderValueDoesNotCrashWhenNoScheduleIsConfigured) was
 * exercised previously. This is money-affecting logic parsing a JSONB shape that is itself a
 * flagged, Actuarial-pending placeholder, so its band-matching and boundary handling
 * (minMonths inclusive, maxMonths exclusive) is exercised here directly.
 *
 * <p>resolveSurrenderChargePercent only touches its own two parameters plus the injected
 * ObjectMapper -- every other PolicyApiImpl constructor dependency is irrelevant to this method
 * and is passed as null.
 */
class PolicyApiImplSurrenderChargeTest {

    private final PolicyApiImpl policyApi = new PolicyApiImpl(
        null, null, null, null, null, null, // policy, account, endorsement, beneficiary, coverage, reservation
        null, null, null, null,             // group scheme, grades, members, member benefits
        null,                               // enrolment rows (refund detail on an exit)
        null, null, null, null, null, null, // party, product, refdata, distribution, underwriting, events
        new ObjectMapper());

    @Test
    void returnsZeroWhenScheduleIsNull() {
        assertEquals(0, BigDecimal.ZERO.compareTo(policyApi.resolveSurrenderChargePercent(null, LocalDate.now())));
    }

    @Test
    void returnsZeroWhenScheduleIsBlank() {
        assertEquals(0, BigDecimal.ZERO.compareTo(policyApi.resolveSurrenderChargePercent("   ", LocalDate.now())));
    }

    @Test
    void returnsZeroWhenScheduleIsAnEmptyArray() {
        // Parses successfully (valid JSON) but has no bands to match -- must fall through to
        // zero, not throw.
        assertEquals(0, BigDecimal.ZERO.compareTo(policyApi.resolveSurrenderChargePercent("[]", LocalDate.now())));
    }

    @Test
    void returnsZeroWhenScheduleIsMalformedJson() {
        // Genuinely unparseable -- must be caught and treated as zero charge, never thrown out
        // to the caller (per this method's own javadoc contract).
        assertEquals(0, BigDecimal.ZERO.compareTo(policyApi.resolveSurrenderChargePercent("not valid json{{{", LocalDate.now())));
    }

    @Test
    void selectsThePercentFromTheBandThatContainsTheCurrentDuration() {
        String schedule = "[{\"minMonths\":0,\"maxMonths\":12,\"chargePercent\":10},{\"minMonths\":12,\"chargePercent\":2}]";
        LocalDate issueDate = LocalDate.now().minusMonths(6); // 6 months in force -> first band (0 <= 6 < 12)
        assertEquals(0, new BigDecimal("10").compareTo(policyApi.resolveSurrenderChargePercent(schedule, issueDate)));
    }

    @Test
    void maxMonthsIsExclusiveAndMinMonthsIsInclusiveAtTheBandBoundary() {
        String schedule = "[{\"minMonths\":0,\"maxMonths\":12,\"chargePercent\":10},{\"minMonths\":12,\"chargePercent\":2}]";
        LocalDate issueDate = LocalDate.now().minusMonths(12); // exactly at the boundary -> second band, not first
        assertEquals(0, new BigDecimal("2").compareTo(policyApi.resolveSurrenderChargePercent(schedule, issueDate)));
    }

    @Test
    void returnsZeroWhenDurationIsPastAllDefinedBands() {
        String schedule = "[{\"minMonths\":0,\"maxMonths\":6,\"chargePercent\":10}]";
        LocalDate issueDate = LocalDate.now().minusMonths(24); // 24 months >= the only band's maxMonths(6) -- no band matches
        assertEquals(0, BigDecimal.ZERO.compareTo(policyApi.resolveSurrenderChargePercent(schedule, issueDate)));
    }
}
