# IFRS 17 I2 — Classification at Sale Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every policy is classified for IFRS 17 at the moment of sale — portfolio, cohort, expected profitability, measurement model, group, sales channel and branch — and the classification can never change afterwards.

**Architecture:** The facts each module owns are captured where they arise. Product carries a portfolio code; each version carries its expected profitability and an optional measurement-model override. Distribution gives every agent a channel and a home branch. Underwriting captures channel and branch on the case, with defaults, editable until issue. Policy stamps these sale facts on the policy at issue, immutably, and announces them on `policy.PolicyIssued`. Finaccounting owns the accounting judgement: from the event it resolves the measurement model against the accounting policy register, finds or creates the IFRS 17 group, and keeps an immutable `policy_classification` record that the I3 posting engine reads (spec §7.2).

**Tech Stack:** Spring Boot / Spring Modulith, JPA, Postgres 16 (Testcontainers), React + TypeScript + zod + react-hook-form, Playwright.

## Global Constraints

- Portfolio codes, exactly: TERM, WL, END, MB, PAR, ULIP, SAV, DEP, IANN, DANN, PEN, GRPL, CRL, FUN (spec §6).
- Profitability buckets: ONEROUS, NO_SIGNIFICANT_RISK, REMAINING; default REMAINING (spec §6).
- Branches: DSM, ARU, MWZ, DOD, ZNZ, a maintained list in refdata. Channels: AGENT, BROKER, BANCASSURANCE, DIRECT, DIGITAL (spec §6).
- Cohort = calendar year of issue (register election COHORT = ANNUAL).
- Measurement models: GMM, VFA, PAA, IFRS9. An override applies only where the register in force on the issue date lists it in MODEL_OVERRIDE_ALLOWED for that portfolio.
- Classification is never changed after issue. A vesting pension is classified again, as a new contract in a new cohort (G-08), by a second immutable record. It never edits the first.
- Run affected test classes by name. Never run Prettier. Never run Maven in Docker. Stop the dev backend before `clean`. Write files with the Edit/Write tools, not shell strings.

## Decisions taken while reading the code (record in the merge message)

- **R1 — The model is resolved in finaccounting from `PolicyIssued`, not inside the issue transaction.**
  - The spec has issue "resolve the measurement model … and stamp group, model … on the policy".
  - The register lives in finaccounting. A synchronous call would make every one of the ~64 issue test classes apply the whole finaccounting migration chain.
  - So policy stamps the sale facts it owns. Finaccounting's `policy_classification` (spec §7.2, "finaccounting keeps its own copy") is where model and group live, immutably.
  - The policy detail screen reads it from finaccounting.
- **R2 — An override the register does not allow cannot refuse the sale (R1 makes classification asynchronous).**
  - The classification records the register's model with basis `OVERRIDE_REFUSED`, and the console shows it.
  - Product cannot check overrides at publish: product → finaccounting would be a module cycle.
- **R3 — Group key `PORTFOLIO-MODEL-YEAR-BUCKET`, e.g. `END-GMM-2026-REM`** (bucket suffixes ONER / NSR / REM).
  - The spec's example `END-2026-PROF` omits the model.
  - A version override (credit life: PAA scheme vs GMM single policy) or a register change would then put two models under one key. IFRS 17 forbids that: a group has one model.
- **R4 — The legacy `product_version.ifrs_measurement_model` (GMM/PAA, mandatory at publish) is retired, not repurposed.**
  - Read as an override, every unit-linked version that stored GMM would contradict the register's VFA.
  - The column becomes nullable and is no longer asked for. The console's publish form asks instead for expected profitability and an optional override.
- **R5 — The case's existing free-text `branch` / `source_of_business` (underwriting V4) stay as they are.**
  - Controlled `branch_code` and `sales_channel` are added beside them.
  - The open-case form's free-text Branch input is replaced by the controlled pickers.
- **R6 — A staff member's home branch is a Keycloak user attribute, `home_branch`, sent as a token claim.**
  - Staff users live in Keycloak. The platform has no staff table.
  - A staff-opened case takes that branch when the claim is present.
- **R7 — Missing facts never invent data in the domain.**
  - At issue, an unset channel defaults to: the agent's channel; else BANCASSURANCE for CREDIT_LIFE; else DIRECT.
  - An unset branch defaults to the agent's home branch, else the refdata `HEAD_OFFICE_BRANCH` (DSM).
  - Development backfill (spec §6) is a one-off script run against the dev DB, recorded in git. It is not a migration.
- **R8 — New columns on hot tables reach the hand-written test migration lists through one mechanical script**, `backend/scripts/dev/append-test-migration.mjs`. It inserts a migration literal after the last literal of the same module in every test file that already lists that module's V1.

## File structure

**refdata:** `db-migrations/refdata/V8__ifrs17_branches_and_channels.sql`.

**product:**
- `db-migrations/product/V27__ifrs17_classification.sql`.
- New api types: `PortfolioCode`, `ProfitabilityBucket`, `Ifrs17Model`, `Ifrs17Terms`.
- Modify: `ProductDefinition`, `ProductVersion`, `ProductApi`/`ProductApiImpl` (createProduct overload, publishVersion deepest overload), `ProductSnapshotView`, `ProductSummaryView`, `CreateProductRequest`, `PublishVersionRequest`, `ProductController`.

**distribution:**
- `db-migrations/distribution/V5__agent_channel_and_home_branch.sql`.
- New api type `SalesChannel`.
- Modify: `Agent`, `AgentView`, `DistributionApi.OnboardAgentRequest` (+ 4-arg constructor), `DistributionApiImpl`.
- New: `updateAgentPlacement`; the controller's `PUT /agents/{agentId}/placement`; `OnboardAgentRequestDto`.

**underwriting:**
- `db-migrations/underwriting/V18__sale_channel_and_branch.sql`.
- Modify: `UnderwritingCase`, `UnderwritingCaseView`, `UnderwritingApi`/`Impl` (defaults at open, `recordSale`), the controller (`PUT /underwriting/cases/{caseId}/sale`, staff claim default, `OpenCaseRequest` optional fields).
- New listener `PolicyIssuedSaleLockListener`.

**policy:**
- `db-migrations/policy/V37__sale_classification.sql`.
- New `domain/SaleClassification`.
- Modify: `Policy`, `PolicyApiImpl` (both issue sites + payload), `PolicyView`.

**finaccounting:**
- `db-migrations/finaccounting/V11__groups_and_policy_classification.sql`.
- New: `domain/GroupOfContracts`, `domain/PolicyClassification`, `infrastructure/GroupOfContractsRepository`, `infrastructure/PolicyClassificationRepository`, `application/PolicyClassifier`, `application/PolicyClassificationEventListener`, `api/PolicyClassificationView`, `api/ModelBasis`, `infrastructure/PolicyClassificationController`.

**keycloak:** `keycloak/staff-realm.json` (mapper + attributes), `scripts/apply-home-branch.sh`.

**dev:** `scripts/dev/append-test-migration.mjs`, `scripts/dev/backfill-ifrs17-classification.sql`.

**OpenAPI:** product, distribution, underwriting, policy, finaccounting, refdata yaml.

**frontend:**
- api/types/stores.
- Product create form (portfolio, defaulted from category); publish form (bucket + override replacing the IFRS model).
- Product detail and drawer.
- Onboard agent (channel, home branch) + agent placement edit.
- Open case (channel, branch pickers) + case detail sale panel (edit until issue).
- Policy detail IFRS 17 panel.
- e2e `staff-ifrs17-classification.spec.ts`.

---

### Task 0: Plan committed, worktree ready

- [ ] Commit this plan on `ifrs17-i2`; `npm ci` in `frontend/`.

### Task 1: Reference data and the migration-list tool

**Files:** Create `db-migrations/refdata/V8__ifrs17_branches_and_channels.sql`, `scripts/dev/append-test-migration.mjs`.

```sql
-- IFRS 17 I2: the controlled lists a sale is classified by (spec §6). Global, like every refdata row.
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('BRANCH', 'DSM', 'Dar es Salaam', 'DSM', 'TZ'),
    ('BRANCH', 'ARU', 'Arusha', 'ARU', 'TZ'),
    ('BRANCH', 'MWZ', 'Mwanza', 'MWZ', 'TZ'),
    ('BRANCH', 'DOD', 'Dodoma', 'DOD', 'TZ'),
    ('BRANCH', 'ZNZ', 'Zanzibar', 'ZNZ', 'TZ'),
    ('SALES_CHANNEL', 'AGENT', 'Tied agent', 'AGENT', 'TZ'),
    ('SALES_CHANNEL', 'BROKER', 'Broker', 'BROKER', 'TZ'),
    ('SALES_CHANNEL', 'BANCASSURANCE', 'Bancassurance', 'BANCASSURANCE', 'TZ'),
    ('SALES_CHANNEL', 'DIRECT', 'Direct', 'DIRECT', 'TZ'),
    ('SALES_CHANNEL', 'DIGITAL', 'Digital', 'DIGITAL', 'TZ'),
    ('HEAD_OFFICE_BRANCH', 'DEFAULT', 'Branch a sale takes when nothing else names one', 'DSM', 'TZ');
```

`append-test-migration.mjs <migrationPath>`: the path is relative to `db-migrations/`, e.g. `product/V27__ifrs17_classification.sql`. The script:
1. Reads the module name and finds every `src/test/java/**/*.java` that contains `"db-migrations/<module>/V1__`.
2. Skips files that already contain the path.
3. Finds the last line containing `"db-migrations/<module>/V`. It inserts a copy of that line after it with the literal replaced, keeping the original's indentation and trailing comma or `)` handling: if the last line ends `");`, the new line ends `");` and the old one becomes `",`.
4. Preserves CRLF.
5. Prints the files it changed.

Pattern-only logic, no shell strings.

- [ ] Run once on a dry copy (a test file) and read the diff by eye.
- [ ] Commit.

### Task 2: Product — portfolio, profitability, override

**Files:** `db-migrations/product/V27__ifrs17_classification.sql`; new `product/api/PortfolioCode.java`, `ProfitabilityBucket.java`, `Ifrs17Model.java`, `Ifrs17Terms.java`; modify the classes listed above.

```sql
ALTER TABLE product.product_definition ADD COLUMN portfolio_code VARCHAR(10)
    CHECK (portfolio_code IN ('TERM','WL','END','MB','PAR','ULIP','SAV','DEP','IANN','DANN','PEN','GRPL','CRL','FUN'));
-- Backfill by what each product's latest version actually is, then by category.
UPDATE product.product_definition d SET portfolio_code = CASE
    WHEN EXISTS (SELECT 1 FROM product.product_version v JOIN product.version_bonus_terms t USING (product_version_id) WHERE v.product_id = d.product_id) THEN 'PAR'
    WHEN EXISTS (SELECT 1 FROM product.product_version v JOIN product.version_vesting_terms t USING (product_version_id) WHERE v.product_id = d.product_id) THEN 'PEN'
    WHEN EXISTS (SELECT 1 FROM product.product_version v JOIN product.deposit_rate_row t USING (product_version_id) WHERE v.product_id = d.product_id) THEN 'DEP'
    WHEN EXISTS (SELECT 1 FROM product.product_version v JOIN product.version_accumulation_terms t USING (product_version_id) WHERE v.product_id = d.product_id) THEN 'SAV'
    WHEN d.category = 'ENDOWMENT' AND EXISTS (SELECT 1 FROM product.product_version v JOIN product.payout_schedule_row t USING (product_version_id) WHERE v.product_id = d.product_id) THEN 'MB'
    ELSE CASE d.category WHEN 'TERM_LIFE' THEN 'TERM' WHEN 'ENDOWMENT' THEN 'END' WHEN 'WHOLE_LIFE' THEN 'WL'
        WHEN 'ANNUITY' THEN 'IANN' WHEN 'UNIT_LINKED' THEN 'ULIP' WHEN 'GROUP_LIFE' THEN 'GRPL'
        WHEN 'EDUCATION_SAVINGS' THEN 'END' WHEN 'CREDIT_LIFE' THEN 'CRL' WHEN 'FUNERAL' THEN 'FUN' END END;
-- A row written without one (raw SQL fixtures) takes its category's portfolio, so the column can be NOT NULL.
CREATE FUNCTION product.default_portfolio_code() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    IF NEW.portfolio_code IS NULL THEN
        NEW.portfolio_code := CASE NEW.category WHEN 'TERM_LIFE' THEN 'TERM' WHEN 'ENDOWMENT' THEN 'END'
            WHEN 'WHOLE_LIFE' THEN 'WL' WHEN 'ANNUITY' THEN 'IANN' WHEN 'UNIT_LINKED' THEN 'ULIP'
            WHEN 'GROUP_LIFE' THEN 'GRPL' WHEN 'EDUCATION_SAVINGS' THEN 'END' WHEN 'CREDIT_LIFE' THEN 'CRL'
            WHEN 'FUNERAL' THEN 'FUN' END;
    END IF; RETURN NEW; END $$;
CREATE TRIGGER trg_default_portfolio_code BEFORE INSERT ON product.product_definition
    FOR EACH ROW EXECUTE FUNCTION product.default_portfolio_code();
ALTER TABLE product.product_definition ALTER COLUMN portfolio_code SET NOT NULL;

ALTER TABLE product.product_version
    ADD COLUMN expected_profitability_bucket VARCHAR(20) NOT NULL DEFAULT 'REMAINING'
        CHECK (expected_profitability_bucket IN ('ONEROUS','NO_SIGNIFICANT_RISK','REMAINING')),
    ADD COLUMN measurement_model_override VARCHAR(10)
        CHECK (measurement_model_override IN ('GMM','VFA','PAA','IFRS9')),
    ALTER COLUMN ifrs_measurement_model DROP NOT NULL;
-- A credit-life product here is a lender's scheme, which the register lets override to PAA.
UPDATE product.product_version v SET measurement_model_override = 'PAA'
  FROM product.product_definition d WHERE d.product_id = v.product_id AND d.category = 'CREDIT_LIFE';
```

Java:

```java
public enum PortfolioCode {
    TERM, WL, END, MB, PAR, ULIP, SAV, DEP, IANN, DANN, PEN, GRPL, CRL, FUN;
    /** The portfolio a product of this category takes when nobody names one (the old createProduct overload). */
    public static PortfolioCode defaultFor(ProductCategory c) {
        return switch (c) {
            case TERM_LIFE -> TERM; case ENDOWMENT, EDUCATION_SAVINGS -> END; case WHOLE_LIFE -> WL;
            case ANNUITY -> IANN; case UNIT_LINKED -> ULIP; case GROUP_LIFE -> GRPL; case CREDIT_LIFE -> CRL;
            case FUNERAL -> FUN;
        };
    }
}
public enum ProfitabilityBucket {
    ONEROUS("ONER"), NO_SIGNIFICANT_RISK("NSR"), REMAINING("REM");
    private final String groupSuffix;
    ProfitabilityBucket(String s) { groupSuffix = s; }
    public String groupSuffix() { return groupSuffix; }
}
public enum Ifrs17Model { GMM, VFA, PAA, IFRS9 }
/** What the actuary signs off at publish (IFRS 17 spec §6): expected profitability, and a model override or none. */
public record Ifrs17Terms(ProfitabilityBucket bucket, Ifrs17Model modelOverride) {
    public static final Ifrs17Terms DEFAULT = new Ifrs17Terms(ProfitabilityBucket.REMAINING, null);
    public Ifrs17Terms { bucket = bucket == null ? ProfitabilityBucket.REMAINING : bucket; }
}
```

API changes:
- `createProduct(code, name, category, currency, PortfolioCode portfolio, createdBy)` is the new canonical method. The old 5-arg one delegates with `PortfolioCode.defaultFor(category)`.
- A new deepest `publishVersion(..., UnitLinkedPlan unitLinkedPlan, Ifrs17Terms ifrs17, String publishedBy)`. The current deepest delegates with `Ifrs17Terms.DEFAULT`. It stores the bucket and override on `ProductVersion`, and the legacy model only if given.
- `ProductSnapshotView` gains `PortfolioCode portfolioCode, ProfitabilityBucket profitabilityBucket, Ifrs17Model modelOverride` (last). `ProductSummaryView` gains `PortfolioCode portfolioCode`.

HTTP:
- `CreateProductRequest` gains `@NotNull PortfolioCode portfolioCode`.
- `PublishVersionRequest`: `ifrsMeasurementModel` becomes optional (no `@NotNull`, documented deprecated). It gains `ProfitabilityBucket expectedProfitabilityBucket` and `Ifrs17Model measurementModelOverride`, both optional.

- [ ] **Test first:** add to the product integration test (the class that covers create and publish; find it by `grep -l "publishVersion" src/test/java/tz/co/nlolo/lifeplatform/product`):
  - a product created with `PortfolioCode.PAR` reads back PAR in the summary and snapshot;
  - the old createProduct gives `TERM` for TERM_LIFE;
  - a version published with `new Ifrs17Terms(ONEROUS, PAA)` exposes both on the snapshot;
  - one published through an older overload exposes REMAINING and no override.
  - Plus a contract test: `POST /products` without `portfolioCode` → 400; publish without `ifrsMeasurementModel` → 201.
- [ ] Implement.
- [ ] `node scripts/dev/append-test-migration.mjs product/V27__ifrs17_classification.sql` — it must touch about 72 files.
- [ ] `./mvnw -B -o -q clean test-compile`, then run the product test classes by name plus `SpecTypeConformanceTest`.
- [ ] Commit.

### Task 3: Distribution — an agent's channel and home branch

**Files:** `db-migrations/distribution/V5__agent_channel_and_home_branch.sql`, `distribution/api/SalesChannel.java` (AGENT, BROKER, BANCASSURANCE, DIRECT, DIGITAL), and the files listed above.

```sql
ALTER TABLE distribution.agent
    ADD COLUMN sales_channel VARCHAR(20) NOT NULL DEFAULT 'AGENT'
        CHECK (sales_channel IN ('AGENT','BROKER','BANCASSURANCE')),
    ADD COLUMN home_branch VARCHAR(10);
UPDATE distribution.agent SET home_branch = 'DSM' WHERE home_branch IS NULL;   -- the agents that exist sell from head office
```

(The agent table's exact name: read `distribution/V1`.)

Behaviour:
- `OnboardAgentRequest(partyId, licenseNumber, licenseExpiryDate, hierarchyParentId, SalesChannel salesChannel, String homeBranch)`, with a 4-arg constructor giving `(AGENT, null)`.
- An agent's channel is AGENT, BROKER or BANCASSURANCE. Refuse DIRECT/DIGITAL with "An agent sells through AGENT, BROKER or BANCASSURANCE".
- A home branch must be a refdata `BRANCH` code: "Unknown branch X".
- `AgentView` gains `salesChannel, homeBranch`.
- `AgentView updateAgentPlacement(UUID agentId, SalesChannel channel, String homeBranch, String by)` (same validation), exposed as `PUT /agents/{agentId}/placement` under the onboarding role gate.

- [ ] **Test first:** onboard with BROKER/ARU reads back; onboard 4-arg reads AGENT/null; placement update to BANCASSURANCE/MWZ; DIRECT refused; `XYZ` branch refused (the test applies refdata V8).
- [ ] Implement.
- [ ] Run the append script for distribution V5 (about 13 files) and refdata V8 (files listing `refdata/V1__`).
- [ ] Run the distribution test classes + contract test.
- [ ] Commit.

### Task 4: Underwriting — channel and branch on the case

**Files:** `db-migrations/underwriting/V18__sale_channel_and_branch.sql` and the files listed above.

```sql
ALTER TABLE underwriting.underwriting_case
    ADD COLUMN sales_channel VARCHAR(20) CHECK (sales_channel IN ('AGENT','BROKER','BANCASSURANCE','DIRECT','DIGITAL')),
    ADD COLUMN branch_code VARCHAR(10),
    ADD COLUMN sale_locked_at TIMESTAMPTZ;
```

Behaviour:
- **Opening a case, in every `openCase` overload and `openMemberEvidenceCase`:**
  - with an agent of record, the case takes the agent's channel and home branch (`distributionApi.getAgent`);
  - without one, the channel is BANCASSURANCE for a CREDIT_LIFE product and DIRECT otherwise, and the branch is null.
- **`UnderwritingCaseView recordSale(UUID caseId, String salesChannel, String branchCode, String by)`:**
  - validates against refdata `SALES_CHANNEL` / `BRANCH`;
  - refused once `sale_locked_at` is set: "The sale is fixed once the policy is issued";
  - exposed as `PUT /underwriting/cases/{caseId}/sale` for the roles that may open a case.
- **Controller:** after opening a case, if the case has no branch and the JWT carries `home_branch`, it calls `recordSale` with the case's channel and that branch. `OpenCaseRequest` and the group request take optional `salesChannel` / `branchCode`, applied the same way after open.
- **`PolicyIssuedSaleLockListener`** (AFTER_COMMIT, REQUIRES_NEW, the finaccounting listener mechanics): on `policy.PolicyIssued` with `underwritingCaseId`, it sets `sale_locked_at` if unset.
- **View:** `UnderwritingCaseView` gains `salesChannel, branchCode, saleLockedAt`.

- [ ] **Test first:**
  - agent-of-record case → agent's channel/branch;
  - no agent → DIRECT/null;
  - credit-life product → BANCASSURANCE;
  - recordSale to DIGITAL/ZNZ;
  - unknown channel refused;
  - after a `policy.PolicyIssued` event for the case, recordSale is refused with the exact message.
- [ ] Implement.
- [ ] Run the append script for underwriting V18 (about 63 files).
- [ ] Run underwriting classes + contract test.
- [ ] Commit.

### Task 5: Policy — the sale stamped at issue, immutably

**Files:** `db-migrations/policy/V37__sale_classification.sql`, `policy/domain/SaleClassification.java`, and the files listed above. Policy's `package-info` stays as it is (it already reads underwriting, product, distribution and refdata).

```sql
ALTER TABLE policy.policy
    ADD COLUMN portfolio_code VARCHAR(10),
    ADD COLUMN cohort_year INTEGER,
    ADD COLUMN profitability_bucket VARCHAR(20),
    ADD COLUMN measurement_model_override VARCHAR(10),
    ADD COLUMN sales_channel VARCHAR(20),
    ADD COLUMN branch_code VARCHAR(10);
-- Fixed at sale (IFRS 17 spec §6): a value, once written, is never changed. Null -> value is allowed once, for the
-- development backfill of policies issued before I2.
CREATE FUNCTION policy.guard_sale_classification() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    IF (OLD.portfolio_code IS NOT NULL AND NEW.portfolio_code IS DISTINCT FROM OLD.portfolio_code)
       OR (OLD.cohort_year IS NOT NULL AND NEW.cohort_year IS DISTINCT FROM OLD.cohort_year)
       OR (OLD.profitability_bucket IS NOT NULL AND NEW.profitability_bucket IS DISTINCT FROM OLD.profitability_bucket)
       OR (OLD.measurement_model_override IS NOT NULL AND NEW.measurement_model_override IS DISTINCT FROM OLD.measurement_model_override)
       OR (OLD.sales_channel IS NOT NULL AND NEW.sales_channel IS DISTINCT FROM OLD.sales_channel)
       OR (OLD.branch_code IS NOT NULL AND NEW.branch_code IS DISTINCT FROM OLD.branch_code) THEN
        RAISE EXCEPTION 'POLICY_CLASSIFICATION_IMMUTABLE: policy % was classified at sale', OLD.policy_number;
    END IF; RETURN NEW; END $$;
CREATE TRIGGER trg_guard_sale_classification BEFORE UPDATE ON policy.policy
    FOR EACH ROW EXECUTE FUNCTION policy.guard_sale_classification();
```

`SaleClassification(String portfolioCode, int cohortYear, String profitabilityBucket, String modelOverride, String salesChannel, String branchCode)`, and `Policy.classifyAtSale(SaleClassification)`.

At both issue sites, before save:
- portfolio, bucket and override come from the product snapshot;
- cohort = issue date's year;
- channel and branch come from the case view if any, then the defaults of R7 (agent via `distributionApi.getAgent`, refdata `HEAD_OFFICE_BRANCH`, which yields null if the code set is absent).

The `PolicyIssued` payload gains `portfolioCode, cohortYear, profitabilityBucket, measurementModelOverride, salesChannel, branchCode`. `PolicyView` gains the same six.

- [ ] **Test first** (policy issue integration test):
  - an issued policy reads back the six facts;
  - the case's DIGITAL/ZNZ wins over the agent's;
  - no case and no agent → DIRECT / DSM;
  - the `PolicyIssued` event carries them;
  - an `UPDATE policy.policy SET sales_channel = 'BROKER'` as the app role is refused with `POLICY_CLASSIFICATION_IMMUTABLE`;
  - the group-scheme path stamps a GRPL portfolio.
- [ ] Implement.
- [ ] Run the append script for policy V37 (about 64 files).
- [ ] Run the policy issue classes, the group-scheme and credit-life scheme classes, and the funeral issue class.
- [ ] Commit.

### Task 6: Finaccounting — groups and the policy classification

**Files:** `db-migrations/finaccounting/V11__groups_and_policy_classification.sql` and the new classes listed above.

```sql
ALTER TABLE finaccounting.group_of_contracts
    ADD COLUMN group_key VARCHAR(40),
    ADD COLUMN portfolio_code VARCHAR(10),
    ADD COLUMN profitability_bucket VARCHAR(20),
    ALTER COLUMN product_id DROP NOT NULL,
    DROP CONSTRAINT IF EXISTS group_of_contracts_measurement_model_check;
DELETE FROM finaccounting.group_of_contracts;   -- M1 placeholder, never written (I1 dropped its ledgers)
ALTER TABLE finaccounting.group_of_contracts
    ALTER COLUMN group_key SET NOT NULL, ALTER COLUMN portfolio_code SET NOT NULL,
    ALTER COLUMN profitability_bucket SET NOT NULL,
    ADD CONSTRAINT group_of_contracts_model_check CHECK (measurement_model IN ('GMM','VFA','PAA','IFRS9'));
CREATE UNIQUE INDEX ux_group_of_contracts_key ON finaccounting.group_of_contracts (tenant_id, group_key);

CREATE TABLE finaccounting.policy_classification (
    tenant_id UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    reason VARCHAR(10) NOT NULL CHECK (reason IN ('ISSUE','VESTING')),
    effective_from DATE NOT NULL,
    group_id UUID NOT NULL REFERENCES finaccounting.group_of_contracts(group_id),
    group_key VARCHAR(40) NOT NULL,
    measurement_model VARCHAR(10) NOT NULL CHECK (measurement_model IN ('GMM','VFA','PAA','IFRS9')),
    model_basis VARCHAR(20) NOT NULL CHECK (model_basis IN ('REGISTER','OVERRIDE','OVERRIDE_REFUSED')),
    register_version INTEGER NOT NULL,
    portfolio_code VARCHAR(10) NOT NULL,
    cohort_year INTEGER NOT NULL,
    profitability_bucket VARCHAR(20) NOT NULL,
    product_id UUID, product_version_id UUID,
    sales_channel VARCHAR(20), branch_code VARCHAR(10),
    classified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, policy_number, reason)
);
-- immutability trigger (refuse UPDATE/DELETE: 'CLASSIFICATION_IMMUTABLE'), RLS + grants as V10 does for its two tables.
```

`PolicyClassifier.classify(Input)`, inside the listener's REQUIRES_NEW transaction:
1. Seeds the register.
2. `register = policyRegister.inForce("MEASUREMENT_MODEL", portfolio, effectiveFrom)`.
3. If the override is present, differs from the register's model and is listed in `inForce("MODEL_OVERRIDE_ALLOWED", portfolio, date)`, basis OVERRIDE; if present but not listed, OVERRIDE_REFUSED with the register's model; otherwise REGISTER.
4. `groupKey = portfolio + "-" + model + "-" + cohort + "-" + bucketSuffix`; finds or creates the group (`INSERT … ON CONFLICT (tenant_id, group_key) DO NOTHING`, then select).
5. Inserts the classification unless `(tenant, policy, reason)` exists, so a redelivery is a no-op.
6. `register_version = policyRegister.currentVersion`.

`PolicyClassificationEventListener` (bean `finaccountingPolicyClassificationEventListener`):
- **`policy.PolicyIssued`:** classifies with reason ISSUE and effectiveFrom = issueDate. Skips, with an info log, a payload without `portfolioCode`, i.e. an event from before I2.
- **`policy.AnnuityVested`:** reads the ISSUE classification. If its model is IFRS9 (a pension in deferral), it classifies reason VESTING, effectiveFrom = vestedOn, portfolio IANN, cohort = vestedOn's year, same bucket/channel/branch, no override (G-08). Otherwise it does nothing.

API `List<PolicyClassificationView> policyClassifications(String policyNumber)`, exposed as `GET /finance/policy-classifications/{policyNumber}` (finance/admin), oldest first.

- [ ] **Test first** (`PolicyClassificationIntegrationTest`, publishing envelopes as `FinaccountingApiIntegrationTest` does):
  - a TERM issue in 2026 → `TERM-GMM-2026-REM`, REGISTER basis, register version 43;
  - a CRL issue with a PAA override → `CRL-PAA-2026-REM`, OVERRIDE;
  - a TERM issue with a PAA override → `TERM-GMM-2026-REM`, OVERRIDE_REFUSED;
  - a redelivery writes nothing new;
  - two policies share one group row;
  - a PEN issue (IFRS9) then `AnnuityVested` 2031-03-01 → a second row `IANN-GMM-2031-REM`, VESTING;
  - an UPDATE of a classification as the app role → `CLASSIFICATION_IMMUTABLE`;
  - a PolicyIssued without portfolioCode → no row;
  - a contract test of the GET (200 with rows, 403 for an underwriter).
- [ ] Implement.
- [ ] Run the append script for finaccounting V11 (about 13 files).
- [ ] Run the finaccounting classes + `TenantTableRlsCoverageIntegrationTest` + `ModularityTests`.
- [ ] Commit.

### Task 7: Keycloak home branch

- `staff-realm.json`: add a `home_branch` user-attribute mapper beside `tenant_id` on both clients; give the seeded staff users `"home_branch": ["DSM"]`, and `staff.finance` `["ARU"]` (so e2e can tell a default from a fallback).
- `scripts/apply-home-branch.sh`, modelled on `apply-spa-client.sh`, idempotent:
  - adds the mapper to `lifeplatform-app` and `lifeplatform-spa` if absent;
  - sets the attribute on the six seeded staff users;
  - verifies by reading the users back, and fails loudly if Keycloak dropped the attribute (user-profile policy).
- [ ] Run it against the dev Keycloak in Task 10 and record the outcome.

### Task 8: API documents and the console

- **OpenAPI:**
  - product: `portfolioCode` on create (required) and the views; `expectedProfitabilityBucket` and `measurementModelOverride` on publish and the snapshot; `ifrsMeasurementModel` optional and deprecated;
  - distribution: placement fields and the PUT;
  - underwriting: case fields, the PUT `/sale`, the open-request fields;
  - policy: the six view fields;
  - finaccounting: the classification GET and its schema.
- **`npm run generate:api`.**
- **Products:**
  - The create form gets a Portfolio select, preselected from the category (changing the category re-defaults it until the user picks one by hand).
  - The publish form replaces "IFRS measurement model" with "Expected profitability" (default Remaining) and "Measurement model override" (default "None — the accounting policy register decides").
  - Product detail and drawer show Portfolio, Expected profitability and Model override.
- **Agents:** onboarding gets Channel (Agent / Broker / Bancassurance, default Agent) and Home branch (required select). Agent detail shows them, with a "Change placement" form.
- **Cases:**
  - The open-case form replaces the free-text Branch with Branch and Channel selects, both optional (blank means the server's default).
  - Case detail gets a "Sale" section showing channel and branch, with an edit form until the sale is locked. After that it reads "Fixed when the policy was issued".
- **Policy detail:** a "Sale & IFRS 17" panel shows portfolio, cohort, expected profitability, override, channel and branch. For finance and admin it adds the finaccounting classification rows: group key, model, basis (OVERRIDE_REFUSED shown as a warning), register version, effective from.
- **Lists for the selects:** `GET /reference-codes/BRANCH` and `/reference-codes/SALES_CHANNEL`, via a small cached store.
- **Unit tests:** the portfolio-default function; the sale form schema; the publish-form schema without the legacy field. Update `publishVersionSchema.test.ts`.
- [ ] `npx tsc -b; npx eslint src e2e; npx vitest run` on the touched folders.
- [ ] Update `e2e/creditLife.ts` (drop `ifrsMeasurementModel: 'PAA'`, send `measurementModelOverride: 'PAA'`) and any spec that fills the old fields.
- [ ] Create `e2e/staff-ifrs17-classification.spec.ts`:
  1. A product created through the console shows the TERM portfolio by default.
  2. A case opened for Amina by an underwriter shows the channel and branch it defaulted to. It changes to DIGITAL/ZNZ, and after issue the sale form is gone.
  3. As finance, the issued policy shows `TERM-GMM-<year>-REM`, basis Register.
- [ ] Commit.

### Task 9: Dev backfill script

`scripts/dev/backfill-ifrs17-classification.sql`, one-off, run once on the dev DB in Task 10. Its header records the date and why.

1. Stamps every policy whose `portfolio_code` is null:
   - portfolio from its product;
   - cohort from issue year (or the created_at year);
   - bucket REMAINING;
   - override from the version;
   - channel AGENT where `agent_of_record_id` is set, else DIRECT;
   - branch DSM.
2. Creates groups and `policy_classification` rows with reason ISSUE and basis REGISTER/OVERRIDE, as `PolicyClassifier` would.
   - The model comes from `finaccounting.accounting_policy_election` in force on the issue date for the portfolio, else `*`.
   - The register version is the tenant's max.

- [ ] Write it, read it against the `PolicyClassifier` logic line by line, and commit.

### Task 10: Dev database, gate, merge

- [ ] Stop the dev backend (PIDs from the process list; never the redhat.java one).
- [ ] Apply refdata V8, product V27, distribution V5, underwriting V18, policy V37 and finaccounting V11 with `psql -v ON_ERROR_STOP=1 -1`; run the backfill; check counts.
- [ ] Run `scripts/apply-home-branch.sh`.
- [ ] Restart the backend from this worktree.
- [ ] Backend gate:
  - `clean test-compile`;
  - every class the append script touched for policy V37 (the issue surface), plus all product, distribution, underwriting and finaccounting test classes;
  - `MigrationScriptCoverageTest`, `ModularityTests`, `SpecTypeConformanceTest`, `TenantTableRlsCoverageIntegrationTest`, `NoCrossModuleJoinTest`.
  - Compare the class counts and grep for OOM.
- [ ] Frontend gate: `tsc`, `eslint`, full `vitest`.
- [ ] Full e2e, with nothing else running; re-run any timeout alone first.
- [ ] Whole-branch self-review against spec §6 and this plan; fix what it finds.
- [ ] Merge `--no-ff` to main, push, update memory.
