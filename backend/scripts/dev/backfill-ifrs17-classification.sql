-- scripts/dev/backfill-ifrs17-classification.sql
-- ONE-OFF DEVELOPMENT BACKFILL for IFRS 17 I2 (classification at sale), recorded here as the spec (§6) requires.
-- Not a migration and never run by tests: production had no policies when I2 shipped, so only the development
-- database holds contracts issued before a policy was classified at sale. Run once, after product V27, distribution
-- V5, underwriting V18, policy V37 and finaccounting V11, as the database owner:
--
--   docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 -1 \
--       < scripts/dev/backfill-ifrs17-classification.sql
--
-- What it assumes (spec §6, "development backfill"): portfolio from the product; channel AGENT where the policy has an
-- agent of record, else DIRECT; branch DSM; expected profitability REMAINING; cohort = issue year; the version's
-- override. The model and group follow PolicyClassifier exactly: the register's election in force on the issue date
-- for the portfolio (else '*'), an override only where MODEL_OVERRIDE_ALLOWED lists it.
-- Idempotent: it only fills what is null and only inserts classifications that are missing.

-- 1. Products: the finer reading V27 left to this script -- a product is what its versions are.
UPDATE product.product_definition d SET portfolio_code = x.portfolio
  FROM (SELECT d2.product_id, CASE
          WHEN EXISTS (SELECT 1 FROM product.product_version v JOIN product.version_bonus_terms t USING (product_version_id)
                        WHERE v.product_id = d2.product_id) THEN 'PAR'
          WHEN EXISTS (SELECT 1 FROM product.product_version v JOIN product.version_vesting_terms t USING (product_version_id)
                        WHERE v.product_id = d2.product_id) THEN 'PEN'
          WHEN EXISTS (SELECT 1 FROM product.product_version v JOIN product.deposit_rate_row t USING (product_version_id)
                        WHERE v.product_id = d2.product_id) THEN 'DEP'
          WHEN EXISTS (SELECT 1 FROM product.product_version v JOIN product.version_accumulation_terms t USING (product_version_id)
                        WHERE v.product_id = d2.product_id) THEN 'SAV'
          WHEN d2.category = 'ENDOWMENT' AND EXISTS (SELECT 1 FROM product.product_version v
                        JOIN product.payout_schedule_row t USING (product_version_id) WHERE v.product_id = d2.product_id) THEN 'MB'
          END AS portfolio
          FROM product.product_definition d2) x
 WHERE x.product_id = d.product_id AND x.portfolio IS NOT NULL AND d.portfolio_code IS DISTINCT FROM x.portfolio
   -- only products no classified policy pins yet: a portfolio a contract was classified under is never moved
   AND NOT EXISTS (SELECT 1 FROM policy.policy p WHERE p.product_id = d.product_id AND p.portfolio_code IS NOT NULL);

-- 2. Policies: the sale facts.
UPDATE policy.policy p SET
    portfolio_code = d.portfolio_code,
    cohort_year = EXTRACT(YEAR FROM COALESCE(p.issue_date, p.created_at::date))::int,
    profitability_bucket = v.expected_profitability_bucket,
    measurement_model_override = v.measurement_model_override,
    sales_channel = CASE WHEN p.agent_of_record_id IS NOT NULL THEN 'AGENT' ELSE 'DIRECT' END,
    branch_code = 'DSM'
  FROM product.product_definition d, product.product_version v
 WHERE d.product_id = p.product_id AND v.product_version_id = p.product_version_id
   AND p.portfolio_code IS NULL;

-- 3. Model per policy, resolved as PolicyClassifier resolves it.
CREATE TEMP TABLE backfill_classification ON COMMIT DROP AS
WITH base AS (
    SELECT p.tenant_id, p.policy_number, COALESCE(p.issue_date, p.created_at::date) AS issued_on, p.portfolio_code,
           p.cohort_year, p.profitability_bucket, p.measurement_model_override AS requested_override,
           p.product_id, p.product_version_id, p.sales_channel, p.branch_code
      FROM policy.policy p
     WHERE p.portfolio_code IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM finaccounting.policy_classification c
                        WHERE c.tenant_id = p.tenant_id AND c.policy_number = p.policy_number AND c.reason = 'ISSUE')
), resolved AS (
    SELECT b.*,
           (SELECT e.election_value FROM finaccounting.accounting_policy_election e
             WHERE e.tenant_id = b.tenant_id AND e.election_key = 'MEASUREMENT_MODEL' AND e.status = 'APPROVED'
               AND e.scope IN (b.portfolio_code, '*') AND e.effective_from <= b.issued_on
             ORDER BY (e.scope = '*'), e.effective_from DESC, e.register_version DESC LIMIT 1) AS register_model,
           (SELECT e.election_value FROM finaccounting.accounting_policy_election e
             WHERE e.tenant_id = b.tenant_id AND e.election_key = 'MODEL_OVERRIDE_ALLOWED' AND e.status = 'APPROVED'
               AND e.scope IN (b.portfolio_code, '*') AND e.effective_from <= b.issued_on
             ORDER BY (e.scope = '*'), e.effective_from DESC, e.register_version DESC LIMIT 1) AS allowed_overrides,
           (SELECT COALESCE(max(e.register_version), 0) FROM finaccounting.accounting_policy_election e
             WHERE e.tenant_id = b.tenant_id) AS register_version
      FROM base b
)
SELECT r.*,
       CASE WHEN r.requested_override IS NULL OR r.requested_override = r.register_model THEN r.register_model
            WHEN r.requested_override = ANY (string_to_array(replace(r.allowed_overrides, ' ', ''), ',')) THEN r.requested_override
            ELSE r.register_model END AS model,
       CASE WHEN r.requested_override IS NULL OR r.requested_override = r.register_model THEN 'REGISTER'
            WHEN r.requested_override = ANY (string_to_array(replace(r.allowed_overrides, ' ', ''), ',')) THEN 'OVERRIDE'
            ELSE 'OVERRIDE_REFUSED' END AS model_basis
  FROM resolved r
 WHERE r.register_model IS NOT NULL;   -- a tenant with no register yet is classified by the application on first use

-- 4. Groups, then the classifications.
INSERT INTO finaccounting.group_of_contracts (tenant_id, cohort_year, measurement_model, status, group_key,
                                              portfolio_code, profitability_bucket, created_by)
SELECT DISTINCT b.tenant_id, b.cohort_year, b.model, 'OPEN',
       b.portfolio_code || '-' || b.model || '-' || b.cohort_year || '-' ||
           CASE b.profitability_bucket WHEN 'ONEROUS' THEN 'ONER' WHEN 'NO_SIGNIFICANT_RISK' THEN 'NSR' ELSE 'REM' END,
       b.portfolio_code, b.profitability_bucket, 'backfill:ifrs17-i2'
  FROM backfill_classification b
ON CONFLICT (tenant_id, group_key) DO NOTHING;

INSERT INTO finaccounting.policy_classification (tenant_id, policy_number, reason, effective_from, group_id, group_key,
       measurement_model, model_basis, register_version, portfolio_code, cohort_year, profitability_bucket,
       requested_override, product_id, product_version_id, sales_channel, branch_code)
SELECT b.tenant_id, b.policy_number, 'ISSUE', b.issued_on, g.group_id, g.group_key, b.model, b.model_basis,
       b.register_version, b.portfolio_code, b.cohort_year, b.profitability_bucket, b.requested_override,
       b.product_id, b.product_version_id, b.sales_channel, b.branch_code
  FROM backfill_classification b
  JOIN finaccounting.group_of_contracts g
    ON g.tenant_id = b.tenant_id
   AND g.group_key = b.portfolio_code || '-' || b.model || '-' || b.cohort_year || '-' ||
           CASE b.profitability_bucket WHEN 'ONEROUS' THEN 'ONER' WHEN 'NO_SIGNIFICANT_RISK' THEN 'NSR' ELSE 'REM' END;
