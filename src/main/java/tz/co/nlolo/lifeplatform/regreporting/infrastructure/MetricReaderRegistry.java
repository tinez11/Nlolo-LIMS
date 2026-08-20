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
     * metrics; reinsurance metrics are never attributed by product -- see
     * {@code ReinsuranceMovement}'s javadoc -- so a filter on them is simply not applied).
     *
     * <p>STOCK metrics load every row with {@code period <= period}; FLOW metrics load only that
     * period's own rows. The switch below is deliberately exhaustive with no {@code default}: an
     * eighteenth {@link MetricName} added later must fail to compile here, not silently return
     * null at generation time.
     */
    public BigDecimal read(UUID tenantId, MetricName metric, String period, String dimensionFilter) {
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

    private static List<ClaimsMovement> filterByClaimType(List<ClaimsMovement> movements, String dimensionFilter) {
        if (dimensionFilter == null) return movements;
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

    /** Cumulative in-force sum assured as of {@code asOfPeriod}: sumAssuredIssued minus
     * sumAssuredTerminated, summed over every row with {@code period <= asOfPeriod}. */
    public static BigDecimal cumulativeSumAssured(List<PolicyMovement> movements, String asOfPeriod) {
        BigDecimal total = BigDecimal.ZERO;
        for (PolicyMovement m : movements) {
            if (m.getPeriod().compareTo(asOfPeriod) > 0) continue;
            total = total.add(m.getSumAssuredIssued()).subtract(m.getSumAssuredTerminated());
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
