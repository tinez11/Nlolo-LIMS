package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import tz.co.nlolo.lifeplatform.regreporting.api.RegreportingValidationException;
import tz.co.nlolo.lifeplatform.regreporting.domain.ClaimsMovement;
import tz.co.nlolo.lifeplatform.regreporting.domain.MetricName;
import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyMovement;
import tz.co.nlolo.lifeplatform.regreporting.domain.PremiumMovement;
import tz.co.nlolo.lifeplatform.regreporting.domain.ReinsuranceMovement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Reads any of the seventeen {@link MetricName} metrics from Task 3's movement fact tables,
 * distinguishing {@code STOCK} (cumulative, as-of-period) metrics from {@code FLOW}
 * (this-period-only) metrics -- see {@code MetricKind}'s javadoc for why the distinction exists.
 *
 * <p>The arithmetic itself lives in {@code public static} helpers that operate on a supplied list
 * rather than fetching one, so it is unit-testable without a database or Spring context
 * ({@code CumulativeMetricTest}). The instance method {@link #read} is the only Spring-managed
 * entry point: it fetches rows via the repositories, applies the dimension filter, and delegates
 * to those static helpers.
 */
@Component
public class MetricReaderRegistry {

    private final PolicyMovementRepository policyMovementRepository;
    private final ClaimsMovementRepository claimsMovementRepository;
    private final PremiumMovementRepository premiumMovementRepository;
    private final ReinsuranceMovementRepository reinsuranceMovementRepository;

    public MetricReaderRegistry(PolicyMovementRepository policyMovementRepository,
                                 ClaimsMovementRepository claimsMovementRepository,
                                 PremiumMovementRepository premiumMovementRepository,
                                 ReinsuranceMovementRepository reinsuranceMovementRepository) {
        this.policyMovementRepository = policyMovementRepository;
        this.claimsMovementRepository = claimsMovementRepository;
        this.premiumMovementRepository = premiumMovementRepository;
        this.reinsuranceMovementRepository = reinsuranceMovementRepository;
    }

    /**
     * Reads {@code metric} for {@code tenantId} as of {@code period}, optionally narrowed by
     * {@code dimensionFilter} (a product id for policy/premium metrics, a claim type for claims
     * metrics).
     *
     * <p><b>An unusable {@code dimensionFilter} is REJECTED, never ignored</b> (M10 final review,
     * I2). A filter on a reinsurance metric used to be silently dropped, so a definition line
     * asking for "ceded premium, product X" quietly reported the TENANT-WIDE total under a label
     * saying otherwise -- an overstated figure on a return, which is strictly worse than a failed
     * generation. A claim-type filter was likewise matched by plain string equality with no
     * validation, so a typo'd or wrong-dimension value returned zero rather than erroring, unlike
     * {@link #parseProductId} which has always thrown on a malformed UUID. Both now throw
     * {@link RegreportingValidationException}.
     *
     * <p>STOCK metrics load every row with {@code period <= period}; FLOW metrics load only that
     * period's own rows. The switch below is deliberately exhaustive with no {@code default}: an
     * eighteenth {@link MetricName} added later must fail to compile here, not silently return
     * null at generation time.
     */
    public BigDecimal read(UUID tenantId, MetricName metric, String period, String dimensionFilter) {
        rejectUnusableDimensionFilter(metric, dimensionFilter);
        return switch (metric) {
            case POLICIES_IN_FORCE -> {
                List<PolicyMovement> movements = filterByProduct(
                    policyMovementRepository.findByTenantIdAndPeriodLessThanEqual(tenantId, period), dimensionFilter);
                yield BigDecimal.valueOf(cumulativePolicyCount(movements, period));
            }
            case SUM_ASSURED_IN_FORCE -> {
                List<PolicyMovement> movements = filterByProduct(
                    policyMovementRepository.findByTenantIdAndPeriodLessThanEqual(tenantId, period), dimensionFilter);
                yield cumulativeSumAssured(movements, period);
            }
            case POLICIES_ISSUED -> {
                List<PolicyMovement> movements = filterByProduct(
                    policyMovementRepository.findByTenantIdAndPeriod(tenantId, period), dimensionFilter);
                yield BigDecimal.valueOf(sumIssued(movements));
            }
            case POLICIES_REINSTATED -> {
                List<PolicyMovement> movements = filterByProduct(
                    policyMovementRepository.findByTenantIdAndPeriod(tenantId, period), dimensionFilter);
                yield BigDecimal.valueOf(sumReinstated(movements));
            }
            case POLICIES_LAPSED -> {
                List<PolicyMovement> movements = filterByProduct(
                    policyMovementRepository.findByTenantIdAndPeriod(tenantId, period), dimensionFilter);
                yield BigDecimal.valueOf(sumLapsed(movements));
            }
            case POLICIES_MATURED -> {
                List<PolicyMovement> movements = filterByProduct(
                    policyMovementRepository.findByTenantIdAndPeriod(tenantId, period), dimensionFilter);
                yield BigDecimal.valueOf(sumMatured(movements));
            }
            case POLICIES_CLAIM_TERMINATED -> {
                List<PolicyMovement> movements = filterByProduct(
                    policyMovementRepository.findByTenantIdAndPeriod(tenantId, period), dimensionFilter);
                yield BigDecimal.valueOf(sumClaimTerminated(movements));
            }
            case NEW_BUSINESS_SUM_ASSURED -> {
                List<PolicyMovement> movements = filterByProduct(
                    policyMovementRepository.findByTenantIdAndPeriod(tenantId, period), dimensionFilter);
                yield sumSumAssuredIssued(movements);
            }
            case CLAIMS_REGISTERED -> {
                List<ClaimsMovement> movements = filterByClaimType(
                    claimsMovementRepository.findByTenantIdAndPeriod(tenantId, period), dimensionFilter);
                yield BigDecimal.valueOf(sumRegistered(movements));
            }
            case CLAIMS_APPROVED -> {
                List<ClaimsMovement> movements = filterByClaimType(
                    claimsMovementRepository.findByTenantIdAndPeriod(tenantId, period), dimensionFilter);
                yield BigDecimal.valueOf(sumApprovedCount(movements));
            }
            case CLAIMS_REJECTED -> {
                List<ClaimsMovement> movements = filterByClaimType(
                    claimsMovementRepository.findByTenantIdAndPeriod(tenantId, period), dimensionFilter);
                yield BigDecimal.valueOf(sumRejected(movements));
            }
            case CLAIMS_SETTLED -> {
                List<ClaimsMovement> movements = filterByClaimType(
                    claimsMovementRepository.findByTenantIdAndPeriod(tenantId, period), dimensionFilter);
                yield BigDecimal.valueOf(sumSettledCount(movements));
            }
            case CLAIMS_APPROVED_AMOUNT -> {
                List<ClaimsMovement> movements = filterByClaimType(
                    claimsMovementRepository.findByTenantIdAndPeriod(tenantId, period), dimensionFilter);
                yield sumApprovedAmount(movements);
            }
            case CLAIMS_SETTLED_AMOUNT -> {
                List<ClaimsMovement> movements = filterByClaimType(
                    claimsMovementRepository.findByTenantIdAndPeriod(tenantId, period), dimensionFilter);
                yield sumSettledAmount(movements);
            }
            case PREMIUM_COLLECTED -> {
                List<PremiumMovement> movements = filterPremiumByProduct(
                    premiumMovementRepository.findByTenantIdAndPeriod(tenantId, period), dimensionFilter);
                yield sumCollected(movements);
            }
            case REINSURANCE_CEDED_RISK -> {
                List<ReinsuranceMovement> movements = reinsuranceMovementRepository
                    .findByTenantIdAndPeriod(tenantId, period).map(List::of).orElseGet(List::of);
                yield sumCededRisk(movements);
            }
            case REINSURANCE_CEDED_PREMIUM -> {
                List<ReinsuranceMovement> movements = reinsuranceMovementRepository
                    .findByTenantIdAndPeriod(tenantId, period).map(List::of).orElseGet(List::of);
                yield sumCededPremium(movements);
            }
        };
    }

    // ---- dimension filtering ----------------------------------------------------------------

    /**
     * The claim-type vocabulary a {@code dimension_filter} on a claims metric may legitimately
     * name: {@code claim_dimension.claim_type}'s CHECK vocabulary (db-migrations/regreporting/V2
     * section 5) PLUS the {@code "UNKNOWN"} sentinel. The sentinel belongs here even though the
     * CHECK does not admit it: it is a real, queryable value in {@code claims_movement.claim_type}
     * (that column deliberately carries no CHECK -- see {@code ProjectionSupport}), so a return
     * line filtering on it to surface unattributed claims is a legitimate thing to ask for.
     */
    private static final Set<String> FILTERABLE_CLAIM_TYPES =
        Set.of("DEATH", "DISABILITY", "CRITICAL_ILLNESS", "MATURITY", "UNKNOWN");

    /**
     * Fails a {@code dimensionFilter} the requested metric cannot honour, BEFORE any row is read.
     * Reinsurance metrics have no product (or any other) dimension to filter by at all -- {@code
     * reinsurance_movement} is one row per {@code (tenant, period)} by design (see its own javadoc)
     * -- so a filter on them cannot be satisfied, only ignored, and ignoring it would hand back a
     * tenant-wide total labelled as a filtered one.
     */
    private static void rejectUnusableDimensionFilter(MetricName metric, String dimensionFilter) {
        if (dimensionFilter == null) return;
        if (metric == MetricName.REINSURANCE_CEDED_RISK || metric == MetricName.REINSURANCE_CEDED_PREMIUM) {
            throw new RegreportingValidationException(
                "dimensionFilter '" + dimensionFilter + "' cannot be applied to " + metric
                + ": reinsurance movements carry no product dimension to filter by (reinsurance_movement "
                + "is one row per tenant and period by design), so a filtered figure is not derivable "
                + "-- remove the dimension_filter from this return definition line, or ask for a "
                + "metric that is attributed by product");
        }
    }

    private static List<PolicyMovement> filterByProduct(List<PolicyMovement> movements, String dimensionFilter) {
        if (dimensionFilter == null) return movements;
        UUID productId = parseProductId(dimensionFilter);
        return movements.stream().filter(m -> productId.equals(m.getProductId())).toList();
    }

    private static List<PremiumMovement> filterPremiumByProduct(List<PremiumMovement> movements, String dimensionFilter) {
        if (dimensionFilter == null) return movements;
        UUID productId = parseProductId(dimensionFilter);
        return movements.stream().filter(m -> productId.equals(m.getProductId())).toList();
    }

    /** Validated against the real vocabulary, not matched blindly: an unrecognised claim type used
     * to filter every row away and report zero, which on a return is indistinguishable from "no
     * claims of this type happened" -- the exact silent-wrong-figure failure {@link #parseProductId}
     * has always refused to allow for a malformed product id (M10 final review, I2). */
    private static List<ClaimsMovement> filterByClaimType(List<ClaimsMovement> movements, String dimensionFilter) {
        if (dimensionFilter == null) return movements;
        if (!FILTERABLE_CLAIM_TYPES.contains(dimensionFilter)) {
            throw new RegreportingValidationException(
                "dimensionFilter '" + dimensionFilter + "' is not a known claim type -- expected one of "
                + new TreeSet<>(FILTERABLE_CLAIM_TYPES) + " (claim_dimension.claim_type's CHECK "
                + "vocabulary plus the UNKNOWN sentinel). An unrecognised value would report zero, "
                + "which on a return is indistinguishable from a genuine zero");
        }
        return movements.stream().filter(m -> dimensionFilter.equals(m.getClaimType())).toList();
    }

    private static UUID parseProductId(String dimensionFilter) {
        try {
            return UUID.fromString(dimensionFilter);
        } catch (IllegalArgumentException e) {
            throw new RegreportingValidationException("dimensionFilter is not a valid product id: " + dimensionFilter);
        }
    }

    // ---- PolicyMovement arithmetic -----------------------------------------------------------

    /** Cumulative in-force policy count as of {@code asOfPeriod}: issued + reinstated - lapsed -
     * matured - claimTerminated, summed over every row with {@code period <= asOfPeriod}. */
    public static long cumulativePolicyCount(List<PolicyMovement> movements, String asOfPeriod) {
        long total = 0;
        for (PolicyMovement m : movements) {
            if (m.getPeriod().compareTo(asOfPeriod) > 0) continue;
            total += m.getPoliciesIssued();
            total += m.getPoliciesReinstated();
            total -= m.getPoliciesLapsed();
            total -= m.getPoliciesMatured();
            total -= m.getPoliciesClaimTerminated();
        }
        return total;
    }

    /**
     * Cumulative in-force sum assured as of {@code asOfPeriod}: what was issued and what members
     * brought, less what terminated and what members took away, summed over every row with
     * {@code period <= asOfPeriod}.
     *
     * <p>The two member measures joined this sum in Plan 5. Before that a group scheme's figure
     * was frozen at its activation total however many borrowers had since joined or been paid
     * out, because {@code policy.GroupMemberAdded} and {@code GroupMemberExited} had no consumer
     * at all — see db-migrations/regreporting/V5.
     */
    public static BigDecimal cumulativeSumAssured(List<PolicyMovement> movements, String asOfPeriod) {
        BigDecimal total = BigDecimal.ZERO;
        for (PolicyMovement m : movements) {
            if (m.getPeriod().compareTo(asOfPeriod) > 0) continue;
            total = total
                .add(m.getSumAssuredIssued())
                .add(m.getSumAssuredMemberAdded())
                .subtract(m.getSumAssuredTerminated())
                .subtract(m.getSumAssuredMemberExited());
        }
        return total;
    }

    /** FLOW: that period's own sum of policies issued. Callers pass only the rows for one period. */
    public static long sumIssued(List<PolicyMovement> movements) {
        long total = 0;
        for (PolicyMovement m : movements) total += m.getPoliciesIssued();
        return total;
    }

    public static long sumReinstated(List<PolicyMovement> movements) {
        long total = 0;
        for (PolicyMovement m : movements) total += m.getPoliciesReinstated();
        return total;
    }

    public static long sumLapsed(List<PolicyMovement> movements) {
        long total = 0;
        for (PolicyMovement m : movements) total += m.getPoliciesLapsed();
        return total;
    }

    public static long sumMatured(List<PolicyMovement> movements) {
        long total = 0;
        for (PolicyMovement m : movements) total += m.getPoliciesMatured();
        return total;
    }

    public static long sumClaimTerminated(List<PolicyMovement> movements) {
        long total = 0;
        for (PolicyMovement m : movements) total += m.getPoliciesClaimTerminated();
        return total;
    }

    /**
     * FLOW: {@code NEW_BUSINESS_SUM_ASSURED}. Deliberately excludes
     * {@code sum_assured_member_added}, and that is a preserved STATUS QUO rather than a claim
     * that excluding it is right.
     *
     * <p>Whether a borrower joining an existing scheme is "new business written" is an actuarial
     * question nobody has answered. As it stands this counts policy activations only, so a
     * credit-life scheme that opens with one borrower and enrols five hundred over the year
     * reports that one borrower's cover — an understatement on any scheme that grows after
     * activation. Changing a reporting definition was not Plan 5's job; recording that it looks
     * wrong is.
     *
     * <p>If it is ever answered the other way this becomes one
     * {@code .add(m.getSumAssuredMemberAdded())} — the column will already hold the right number.
     * That is precisely why Plan 5 added separate columns instead of merging into
     * {@code sum_assured_issued}: merged, the distinction could never be recovered.
     */
    public static BigDecimal sumSumAssuredIssued(List<PolicyMovement> movements) {
        BigDecimal total = BigDecimal.ZERO;
        for (PolicyMovement m : movements) total = total.add(m.getSumAssuredIssued());
        return total;
    }

    // ---- ClaimsMovement arithmetic -----------------------------------------------------------

    public static long sumRegistered(List<ClaimsMovement> movements) {
        long total = 0;
        for (ClaimsMovement m : movements) total += m.getRegisteredCount();
        return total;
    }

    public static long sumApprovedCount(List<ClaimsMovement> movements) {
        long total = 0;
        for (ClaimsMovement m : movements) total += m.getApprovedCount();
        return total;
    }

    public static long sumRejected(List<ClaimsMovement> movements) {
        long total = 0;
        for (ClaimsMovement m : movements) total += m.getRejectedCount();
        return total;
    }

    public static long sumSettledCount(List<ClaimsMovement> movements) {
        long total = 0;
        for (ClaimsMovement m : movements) total += m.getSettledCount();
        return total;
    }

    public static BigDecimal sumApprovedAmount(List<ClaimsMovement> movements) {
        BigDecimal total = BigDecimal.ZERO;
        for (ClaimsMovement m : movements) total = total.add(m.getApprovedAmount());
        return total;
    }

    public static BigDecimal sumSettledAmount(List<ClaimsMovement> movements) {
        BigDecimal total = BigDecimal.ZERO;
        for (ClaimsMovement m : movements) total = total.add(m.getSettledAmount());
        return total;
    }

    // ---- PremiumMovement arithmetic ----------------------------------------------------------

    public static BigDecimal sumCollected(List<PremiumMovement> movements) {
        BigDecimal total = BigDecimal.ZERO;
        for (PremiumMovement m : movements) total = total.add(m.getCollectedAmount());
        return total;
    }

    // ---- ReinsuranceMovement arithmetic -------------------------------------------------------

    public static BigDecimal sumCededRisk(List<ReinsuranceMovement> movements) {
        BigDecimal total = BigDecimal.ZERO;
        for (ReinsuranceMovement m : movements) total = total.add(m.getCededRiskAmount());
        return total;
    }

    public static BigDecimal sumCededPremium(List<ReinsuranceMovement> movements) {
        BigDecimal total = BigDecimal.ZERO;
        for (ReinsuranceMovement m : movements) total = total.add(m.getCededPremiumAmount());
        return total;
    }
}
