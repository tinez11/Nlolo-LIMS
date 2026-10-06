package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.api.ModelBasis;
import tz.co.nlolo.lifeplatform.finaccounting.api.PolicyClassificationView;
import tz.co.nlolo.lifeplatform.finaccounting.api.PolicyElectionView;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountSeeder;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Classifies a contract for IFRS 17 (I2, spec §6 and §7.2) from the facts it was sold with: resolves the measurement
 * model under the accounting policy register in force on the date, finds or creates the group of contracts, and keeps
 * finaccounting's own, never-changed copy of the classification -- what posting reads its dimensions from (I3).
 *
 * <p>The model: the register's MEASUREMENT_MODEL for the portfolio (else the company-wide one). A version's override
 * counts only where MODEL_OVERRIDE_ALLOWED lists it for the portfolio; one it does not list is recorded as
 * OVERRIDE_REFUSED and the register's model applies -- the sale went through, so the classification cannot refuse it,
 * and the product's configuration is what has to be corrected.
 *
 * <p>The group key is PORTFOLIO-MODEL-YEAR-BUCKET (e.g. END-GMM-2026-REM): a group has one measurement model, and two
 * contracts of one portfolio, cohort and bucket can differ in it (an override, a register change within the year).
 *
 * <p>JDBC rather than JPA for the two writes: find-or-create a group is an INSERT ... ON CONFLICT, and a redelivered
 * event must write nothing, which the classification's primary key decides in the same statement.
 */
@Component
class PolicyClassifier {

    /** What a contract was sold with, and on which date it is classified. */
    record Input(UUID tenantId, String policyNumber, String reason, LocalDate effectiveFrom, String portfolioCode,
                 int cohortYear, String profitabilityBucket, String requestedOverride, UUID productId,
                 UUID productVersionId, String salesChannel, String branchCode) {}

    private final PolicyRegister register;
    private final ChartOfAccountSeeder seeder;
    private final JdbcTemplate jdbc;

    PolicyClassifier(PolicyRegister register, ChartOfAccountSeeder seeder, JdbcTemplate jdbc) {
        this.register = register;
        this.seeder = seeder;
        this.jdbc = jdbc;
    }

    /** Idempotent: a contract already classified for this reason is left as it is. */
    void classify(Input in) {
        seeder.seedPolicyRegisterIfAbsent(in.tenantId());
        String registerModel = register.inForce("MEASUREMENT_MODEL", in.portfolioCode(), in.effectiveFrom())
            .map(PolicyElectionView::value)
            .orElseThrow(() -> new IllegalStateException("The accounting policy register has no measurement model"
                + " for " + in.portfolioCode() + " on " + in.effectiveFrom()));

        String model = registerModel;
        ModelBasis basis = ModelBasis.REGISTER;
        String override = in.requestedOverride();
        if (override != null && !override.equals(registerModel)) {
            List<String> allowed = register.inForce("MODEL_OVERRIDE_ALLOWED", in.portfolioCode(), in.effectiveFrom())
                .map(e -> Arrays.stream(e.value().split(",")).map(String::trim).toList())
                .orElse(List.of());
            if (allowed.contains(override)) {
                model = override;
                basis = ModelBasis.OVERRIDE;
            } else {
                basis = ModelBasis.OVERRIDE_REFUSED;
            }
        }

        String bucket = in.profitabilityBucket() != null ? in.profitabilityBucket() : "REMAINING";
        String groupKey = in.portfolioCode() + "-" + model + "-" + in.cohortYear() + "-" + suffix(bucket);
        jdbc.update("INSERT INTO finaccounting.group_of_contracts (tenant_id, cohort_year, measurement_model, status,"
                + " group_key, portfolio_code, profitability_bucket, created_by)"
                + " VALUES (?, ?, ?, 'OPEN', ?, ?, ?, 'system:classification') ON CONFLICT (tenant_id, group_key) DO NOTHING",
            in.tenantId(), in.cohortYear(), model, groupKey, in.portfolioCode(), bucket);
        UUID groupId = jdbc.queryForObject("SELECT group_id FROM finaccounting.group_of_contracts"
            + " WHERE tenant_id = ? AND group_key = ?", UUID.class, in.tenantId(), groupKey);

        jdbc.update("INSERT INTO finaccounting.policy_classification (tenant_id, policy_number, reason, effective_from,"
                + " group_id, group_key, measurement_model, model_basis, register_version, portfolio_code, cohort_year,"
                + " profitability_bucket, requested_override, product_id, product_version_id, sales_channel, branch_code)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (tenant_id, policy_number, reason) DO NOTHING",
            in.tenantId(), in.policyNumber(), in.reason(), Date.valueOf(in.effectiveFrom()), groupId, groupKey, model,
            basis.name(), register.currentVersion(in.tenantId()), in.portfolioCode(), in.cohortYear(), bucket,
            override, in.productId(), in.productVersionId(), in.salesChannel(), in.branchCode());
    }

    /** A contract's classifications, oldest first. */
    List<PolicyClassificationView> classifications(UUID tenantId, String policyNumber) {
        return jdbc.query("SELECT * FROM finaccounting.policy_classification WHERE tenant_id = ? AND policy_number = ?"
                + " ORDER BY effective_from, classified_at",
            (rs, i) -> new PolicyClassificationView(rs.getString("policy_number"), rs.getString("reason"),
                rs.getDate("effective_from").toLocalDate(), rs.getString("group_key"), rs.getString("measurement_model"),
                ModelBasis.valueOf(rs.getString("model_basis")), rs.getString("requested_override"),
                rs.getInt("register_version"), rs.getString("portfolio_code"), rs.getInt("cohort_year"),
                rs.getString("profitability_bucket"), rs.getString("sales_channel"), rs.getString("branch_code"),
                rs.getObject("classified_at", Timestamp.class).toInstant()),
            tenantId, policyNumber);
    }

    /** What posting reads off a contract's classification (I3a): its model and the line dimensions. */
    record InForce(String groupKey, String model, UUID productId, String portfolio, String channel, String branch) {}

    /**
     * The classification in force on {@code date}: the latest one effective by then (a vested pension's VESTING row
     * from its vesting date). An event dated before the contract's first classification -- a backdated issue -- reads
     * that first one: a classified contract is never unclassified. The product, which a VESTING row does not repeat,
     * comes from the ISSUE row.
     */
    Optional<InForce> inForce(UUID tenantId, String policyNumber, LocalDate date) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM finaccounting.policy_classification"
            + " WHERE tenant_id = ? AND policy_number = ? ORDER BY effective_from, classified_at", tenantId, policyNumber);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> chosen = rows.get(0);
        for (Map<String, Object> row : rows) {
            if (!((Date) row.get("effective_from")).toLocalDate().isAfter(date)) {
                chosen = row;
            }
        }
        UUID product = (UUID) chosen.get("product_id");
        if (product == null) {
            product = rows.stream().map(r -> (UUID) r.get("product_id")).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        }
        return Optional.of(new InForce((String) chosen.get("group_key"), (String) chosen.get("measurement_model"), product,
            (String) chosen.get("portfolio_code"), (String) chosen.get("sales_channel"),
            (String) chosen.get("branch_code")));
    }

    /** The ISSUE classification, if the contract has one. */
    Optional<PolicyClassificationView> atIssue(UUID tenantId, String policyNumber) {
        return classifications(tenantId, policyNumber).stream().filter(c -> "ISSUE".equals(c.reason())).findFirst();
    }

    private static String suffix(String bucket) {
        return switch (bucket) {
            case "ONEROUS" -> "ONER";
            case "NO_SIGNIFICANT_RISK" -> "NSR";
            default -> "REM";
        };
    }
}
