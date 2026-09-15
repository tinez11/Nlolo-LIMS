# Batch 3 TIRA Filing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make it impossible to publish a product version without recording the TIRA filing reference and approval date that authorise it.

**Architecture:** A `TiraFiling` record validates itself, a `V12` migration adds two nullable columns with a `NOT VALID` check so the 133 existing versions are grandfathered, and every `publishVersion` overload takes the filing as a required argument — deliberately with no defaulting overload, since that would be a hole exactly where the requirement lives. The filing reads back on `VersionRatingView`.

**Tech Stack:** Java 21, Spring Boot 3 / Spring Modulith, Hibernate (`ddl-auto: none`), PostgreSQL 16, numbered SQL applied via `MigrationTestSupport`, JUnit 5 + Testcontainers + AssertJ, React 19 + Zod + React Hook Form, Vitest, Playwright.

## Global Constraints

- Design spec: `backend/docs/superpowers/specs/2026-09-15-batch3-tira-filing-design.md`.
- Branch: `batch3-tira-filing`, already created off `main` at `8533e75`.
- Run `./mvnw` on the **host**. Never run Maven inside Docker.
- **Stop the dev backend before any `clean` target.** `clean` deletes `target/` under the live JVM and produces ~20 bogus failures across untouched modules.
- **Run one suite at a time.** Never vitest during Playwright, never two Maven builds, never vitest beside a full Maven run — that last one truncates the vitest run and still prints "passed".
- Never run Prettier.
- Commit messages end with: `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`
- Baseline on `main`: backend **1153** tests green, frontend **90** product-scope tests green, 22 e2e green.
- **This batch implements half the audit finding.** One ADMIN can still change a live price in one call. Nothing in any commit message or comment may imply otherwise.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `product/api/TiraFiling.java` | The reference, the date, and the rules they must satisfy | 1 |
| `test/.../ProductFilingFixture.java` | One shared `ANY_FILING` constant for ~103 call sites | 2 |
| `db-migrations/product/V12__tira_filing.sql` | Two nullable columns, `NOT VALID` check | 2 |
| `product/domain/ProductVersion.java` | Stores it; `applyTiraFiling` / `getTiraFiling` | 2 |
| `product/api/ProductApi.java` | All four `publishVersion` overloads; `VersionRatingView` | 2, 3 |
| `product/application/ProductApiImpl.java` | Refuses a null filing; reads it back | 2, 3 |
| `product/infrastructure/` + `openapi-product.yaml` | Wire shape | 3 |
| `frontend/.../publishVersionSchema.ts` + form + `ProductDetailPage` | Authoring and display | 4 |
| 46 backend test classes | V12 in their migration lists | 2 |

---

## Task 1: `TiraFiling` validates itself

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/TiraFiling.java`
- Create: `backend/src/test/java/tz/co/nlolo/lifeplatform/product/application/TiraFilingTest.java`

**Interfaces:**
- Produces: `TiraFiling(String reference, LocalDate approvalDate)` — compact constructor trims the reference, and throws `IllegalArgumentException` for a null/blank reference, a null date, or a date after today. No `none()` factory: there is no such thing as a filing that is present and empty.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.product.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.TiraFiling;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The filing's own rules, without a Spring context.
 *
 * <p>Validation lives in the record rather than in {@code ProductApiImpl} so no caller can
 * construct an invalid filing to pass anywhere — the same arrangement as
 * {@code EligibilityBounds}'s ordering invariant and {@code FrequencyLoading}'s 0-100 bounds.
 */
class TiraFilingTest {

    @Test
    void aFilingApprovedInThePastIsValid() {
        TiraFiling filing = new TiraFiling("TIRA/LIFE/2026/0042", LocalDate.of(2026, 1, 15));
        assertThat(filing.reference()).isEqualTo("TIRA/LIFE/2026/0042");
        assertThat(filing.approvalDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void aFilingApprovedTodayIsValid() {
        assertThatCode(() -> new TiraFiling("TIRA/LIFE/2026/0043", LocalDate.now()))
            .doesNotThrowAnyException();
    }

    @Test
    void aFilingApprovedTomorrowIsRefused() {
        assertThatThrownBy(() -> new TiraFiling("TIRA/LIFE/2026/0044", LocalDate.now().plusDays(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("future");
    }

    @Test
    void aBlankReferenceIsRefused() {
        for (String blank : new String[] { null, "", "   " }) {
            assertThatThrownBy(() -> new TiraFiling(blank, LocalDate.of(2026, 1, 15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference");
        }
    }

    @Test
    void aMissingApprovalDateIsRefused() {
        assertThatThrownBy(() -> new TiraFiling("TIRA/LIFE/2026/0045", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("approval date");
    }

    @Test
    void theReferenceIsTrimmed() {
        assertThat(new TiraFiling("  TIRA/LIFE/2026/0046  ", LocalDate.of(2026, 1, 15)).reference())
            .isEqualTo("TIRA/LIFE/2026/0046");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```
cd backend && ./mvnw test -Dtest=TiraFilingTest
```

Expected: compilation failure — `TiraFiling` does not exist.

- [ ] **Step 3: Write the record**

```java
package tz.co.nlolo.lifeplatform.product.api;

import java.time.LocalDate;

/**
 * The TIRA filing that authorises a product version to be sold.
 *
 * <p>In Tanzania a product and its rates must be filed with and approved by TIRA before sale.
 * Nothing on this platform recorded that: publishing a version was one ADMIN call with no
 * reference, no approval date, and no evidence the regulator had ever seen the rates.
 *
 * <p><b>Required on every publish, with no defaulting overload.</b> An optional compliance field
 * is one nobody fills in, and a convenience overload that defaulted it would be a hole exactly
 * where the requirement lives — which is how "mandatory" quietly becomes "optional in practice".
 *
 * <p>Validation lives here rather than in the publish path so that no caller can construct an
 * invalid filing to pass anywhere, the same arrangement as {@link EligibilityBounds}'s ordering
 * invariant and {@link FrequencyLoading}'s 0-100 bounds.
 *
 * <p><b>What this does NOT check.</b> That the version's effective date falls on or after
 * {@code approvalDate} — selling before the regulator approved — is a real compliance failure and
 * is deliberately not refused. Corrections on this platform are republishes that carry the
 * original's effective date while their filing is dated later, and a hard gate with no override
 * path produces invented dates rather than compliance. Recorded as an open item in the design
 * spec. Nothing verifies the reference corresponds to a real filing either; this records what a
 * human asserts.
 */
public record TiraFiling(String reference, LocalDate approvalDate) {

    public TiraFiling {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException(
                "A TIRA filing reference is required -- a product version may not be published"
                    + " without the filing that authorises it");
        }
        reference = reference.trim();
        if (approvalDate == null) {
            throw new IllegalArgumentException(
                "A TIRA approval date is required alongside the filing reference");
        }
        if (approvalDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException(
                "TIRA approval date " + approvalDate + " is in the future; an approval that has"
                    + " not happened cannot authorise a product");
        }
    }
}
```

- [ ] **Step 4: Run the test**

```
cd backend && ./mvnw test -Dtest=TiraFilingTest
```

Expected: 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/TiraFiling.java backend/src/test/java/tz/co/nlolo/lifeplatform/product/application/TiraFilingTest.java
git commit -m "feat(product): a TIRA filing, and the rules it must satisfy" -m "In Tanzania a product and its rates must be filed with and approved by TIRA before sale, and nothing on this platform recorded that. The record validates itself so no caller can construct an invalid filing to pass anywhere -- the same arrangement as EligibilityBounds and FrequencyLoading." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: the filing becomes required, everywhere

**This task is indivisible.** Adding the parameter breaks compilation across 35 test classes, so the tree does not compile between the API change and the last call-site fix. It ends green; it cannot end green earlier.

**Files:**
- Create: `backend/db-migrations/product/V12__tira_filing.sql`
- Create: `backend/src/test/java/tz/co/nlolo/lifeplatform/ProductFilingFixture.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/domain/ProductVersion.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/ProductApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java`
- Modify: ~35 backend test classes (the call sites) and all 46 migration lists

**Interfaces:**
- Consumes: `TiraFiling` from Task 1.
- Produces: all four `publishVersion` overloads take `TiraFiling tiraFiling` **immediately before** `String publishedBy`. `ProductVersion.applyTiraFiling(TiraFiling)` / `getTiraFiling()` returning `null` for a grandfathered row. `ProductFilingFixture.ANY_FILING`.

- [ ] **Step 1: Write the migration**

Create `backend/db-migrations/product/V12__tira_filing.sql`:

```sql
-- The TIRA filing that authorises a product version to be sold.
--
-- In Tanzania a product and its rates must be filed with and approved by TIRA before sale.
-- Publishing a version recorded none of it: one ADMIN POST, no reference, no approval date, no
-- evidence the regulator had ever seen the rates -- on the single most financially consequential
-- action this platform performs.
--
-- NULLABLE with a NOT VALID check, not NOT NULL. No filing reference exists for any of the 133
-- versions already in this database, and inventing one would be worse than a null: it would be a
-- fabricated compliance record. NOT VALID enforces the rule on every insert and update from here
-- on while declining to re-litigate history -- exactly what V5, V8 and V9 each did, and each
-- recorded why. The application check in publishVersion runs first, so the failure names the
-- missing field instead of surfacing as a raw constraint violation.
--
-- VARCHAR(60) rather than a tighter guess: no TIRA reference format is recorded anywhere in this
-- repo, and this platform has twice been bitten by a column too short for a value the system
-- already produces (M7's VARCHAR(15) against a 16-character event name, M9's VARCHAR(30) against
-- a 31-character one).
--
-- WHAT THIS DOES NOT DO: it does not introduce separation of duties. One ADMIN can still change a
-- live product's price in a single call, now accompanied by a reference they typed themselves.
-- The maker-checker finding from the product audit stays OPEN.

ALTER TABLE product.product_version
    ADD COLUMN tira_filing_reference VARCHAR(60),
    ADD COLUMN tira_approval_date    DATE;

ALTER TABLE product.product_version
    ADD CONSTRAINT product_version_tira_filing_recorded
        CHECK (tira_filing_reference IS NOT NULL AND tira_approval_date IS NOT NULL)
        NOT VALID;

COMMENT ON COLUMN product.product_version.tira_filing_reference IS
    'The TIRA filing that authorises this version. NULL only on a version published before V12; '
    'nothing published after it can be null. Not verified against TIRA -- this records what a '
    'human asserts.';
```

- [ ] **Step 2: Store it on the version**

In `ProductVersion`, beside the frequency loading columns:

```java
    // The TIRA filing that authorises this version (V12). Nullable ONLY for the versions that
    // predate the rule; publishVersion refuses a null for anything new.
    @Column(name = "tira_filing_reference")
    private String tiraFilingReference;

    @Column(name = "tira_approval_date")
    private LocalDate tiraApprovalDate;
```

and beside `applyFrequencyLoading` / `getFrequencyLoading`:

```java
    public void applyTiraFiling(TiraFiling filing) {
        this.tiraFilingReference = filing.reference();
        this.tiraApprovalDate = filing.approvalDate();
    }

    /**
     * The filing, or NULL for a version published before V12.
     *
     * <p>Null rather than an empty {@link TiraFiling}: there is no such thing as a filing that is
     * present and empty, and the record's own constructor would refuse to build one.
     */
    public TiraFiling getTiraFiling() {
        return tiraFilingReference == null || tiraApprovalDate == null
            ? null
            : new TiraFiling(tiraFilingReference, tiraApprovalDate);
    }
```

Add `import tz.co.nlolo.lifeplatform.product.api.TiraFiling;`.

- [ ] **Step 3: Add the parameter to all four overloads**

In `ProductApi`, insert `TiraFiling tiraFiling,` immediately before `String publishedBy` on **each** of the four `publishVersion` declarations, and add to the fullest one's javadoc:

```java
     * <p>{@code tiraFiling} is REQUIRED on every overload, and there is deliberately no
     * convenience form that defaults it. An optional compliance field is one nobody fills in.
```

In `ProductApiImpl`, make the same four signature changes, have the three delegating overloads pass `tiraFiling` through, and in the fullest overload add this as the **first** validation, before the fund-definition and rating-table checks:

```java
        // First, so the message is about the filing rather than about a rating table the caller
        // may not have reached yet. TiraFiling validates its own contents; what it cannot do is
        // object to its own absence.
        if (tiraFiling == null) {
            throw new InvalidProductVersionException(
                "A TIRA filing reference and approval date are required to publish a product"
                    + " version -- a version may not exist without the filing that authorises it");
        }
```

and apply it to the version beside the other `apply*` calls:

```java
        version.applyTiraFiling(tiraFiling);
```

- [ ] **Step 4: Create the shared test fixture**

Create `backend/src/test/java/tz/co/nlolo/lifeplatform/ProductFilingFixture.java`:

```java
package tz.co.nlolo.lifeplatform;

import tz.co.nlolo.lifeplatform.product.api.TiraFiling;

import java.time.LocalDate;

/**
 * One filing for every test that publishes a product version.
 *
 * <p>A fixed past date rather than {@code LocalDate.now()}: the filing is compared against today
 * only to refuse future dates, and a constant keeps every test independent of the clock.
 *
 * <p>Shared rather than repeated at ~103 call sites because none of those tests is about the
 * filing. The ones that ARE about it build their own.
 */
public final class ProductFilingFixture {

    public static final TiraFiling ANY_FILING =
        new TiraFiling("TIRA/TEST/0001", LocalDate.of(2020, 1, 1));

    private ProductFilingFixture() {}
}
```

- [ ] **Step 5: Add V12 to all 46 migration lists**

The columns are nullable so a missing V12 does not break inserts — but `getTiraFiling()` selects them, so a class without the migration fails at its first product-version read with `column "tira_filing_reference" does not exist`.

```bash
cd backend && node -e "
const fs=require('fs'),path=require('path');
const V11='db-migrations/product/V11__frequency_loading.sql';
const V12='db-migrations/product/V12__tira_filing.sql';
function walk(d,out=[]){for(const e of fs.readdirSync(d,{withFileTypes:true})){const p=path.join(d,e.name);if(e.isDirectory())walk(p,out);else if(e.name.endsWith('.java'))out.push(p);}return out;}
let changed=0;
for(const f of walk('src/test')){
  const raw=fs.readFileSync(f,'utf8');
  if(!raw.includes(V11)||raw.includes(V12))continue;
  const eol=raw.includes('\r\n')?'\r\n':'\n';
  const out=[];
  for(const line of raw.split(/\r?\n/)){
    if(line.includes(V11)){
      const indent=line.match(/^\s*/)[0];
      if(line.trimEnd().endsWith(');')){
        out.push(line.replace(/\"\);\s*\$/,'\",').replace(/\"\);\$/,'\",'));
        out.push(indent+'\"'+V12+'\");');
      } else { out.push(line); out.push(indent+'\"'+V12+'\",'); }
    } else out.push(line);
  }
  fs.writeFileSync(f,out.join(eol)); changed++;
}
console.log('changed',changed);
"
grep -rl "V12__tira_filing.sql" src/test --include="*.java" | wc -l
```

Expected: 46.

- [ ] **Step 6: Insert the argument at every call site, by paren-matching**

**Do not do this with a text substitution on the trailing argument.** `"actuary");` alone appears 115 times across the test tree on calls that are not `publishVersion`, and a blind replace would corrupt them.

This script finds each `publishVersion(` call, matches parentheses while respecting string literals and comments, locates the **last top-level comma**, and inserts the fixture reference before the final argument:

```bash
cd backend && node -e "
const fs=require('fs'),path=require('path');
function walk(d,out=[]){for(const e of fs.readdirSync(d,{withFileTypes:true})){const p=path.join(d,e.name);if(e.isDirectory())walk(p,out);else if(e.name.endsWith('.java'))out.push(p);}return out;}
let files=0,calls=0;
for(const f of walk('src/test')){
  let s=fs.readFileSync(f,'utf8');
  if(!s.includes('publishVersion('))continue;
  let out='',i=0,touched=0;
  while(true){
    const at=s.indexOf('publishVersion(',i);
    if(at<0){out+=s.slice(i);break;}
    const open=at+'publishVersion('.length-1;
    // Match to the closing paren, tracking string/char literals.
    let d=0,j=open,inStr=false,inChr=false,commas=[];
    for(;j<s.length;j++){
      const c=s[j],p=s[j-1];
      if(inStr){ if(c==='\"'&&p!=='\\\\')inStr=false; continue; }
      if(inChr){ if(c===\"'\"&&p!=='\\\\')inChr=false; continue; }
      if(c==='\"'){inStr=true;continue;}
      if(c===\"'\"){inChr=true;continue;}
      if(c==='(')d++;
      else if(c===')'){d--; if(d===0)break;}
      else if(c===','&&d===1)commas.push(j);
    }
    if(j>=s.length||commas.length===0){out+=s.slice(i,at+1);i=at+1;continue;}
    const lastComma=commas[commas.length-1];
    let k=lastComma+1; while(k<j&&/\s/.test(s[k]))k++;
    out+=s.slice(i,k)+'ANY_FILING, ';
    i=k; touched++;
  }
  if(touched){
    // Static import, once, after the last existing import line.
    if(!out.includes('ProductFilingFixture.ANY_FILING')){
      const lines=out.split(/\r?\n/);
      let last=-1; lines.forEach((l,n)=>{ if(/^import /.test(l))last=n; });
      lines.splice(last+1,0,'import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;');
      out=lines.join(out.includes('\r\n')?'\r\n':'\n');
    }
    fs.writeFileSync(f,out); files++; calls+=touched;
  }
}
console.log('inserted into',calls,'calls across',files,'files');
"
```

Expected: about 103 calls across 35 files. The exact number does not matter; the compiler in the next step is the authority.

- [ ] **Step 7: Stop the dev backend, then compile**

`clean` under a live JVM produces ~20 bogus failures across untouched modules. Find the process listening on 8080 and stop it.

```
cd backend && ./mvnw clean test-compile
```

Expected eventually: BUILD SUCCESS. **The compiler is the worklist.** Any call site the script missed appears here as `method publishVersion ... cannot be applied to given types` with a file and line — fix each by hand and re-run until clean. Do not weaken the API to make an error go away.

- [ ] **Step 8: Write the publish-refusal and round-trip tests**

Add to `ProductApiIntegrationTest` (which by now has `ANY_FILING` imported):

```java
    @Test
    void publishVersionRefusesAVersionWithNoTiraFiling() {
        ProductSummaryView product = productApi.createProduct("TERM-B3-NOFILE", "Unfiled",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        InvalidProductVersionException thrown = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null, null, "actuary@nlolo.co.tz"));

        assertThat(thrown.getMessage()).contains("TIRA filing");
    }

    @Test
    void aPublishedVersionCarriesItsTiraFiling() {
        ProductSummaryView product = productApi.createProduct("TERM-B3-FILED", "Filed",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, new TiraFiling("TIRA/LIFE/2026/0099", LocalDate.of(2026, 2, 1)), "actuary@nlolo.co.tz");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        TiraFiling filing = productVersionRepository.findById(versionId).orElseThrow().getTiraFiling();
        assertThat(filing.reference()).isEqualTo("TIRA/LIFE/2026/0099");
        assertThat(filing.approvalDate()).isEqualTo(LocalDate.of(2026, 2, 1));
    }

    /**
     * A version published before V12 reads back null, and this state cannot be produced through
     * the API any more -- no publish can omit the filing. The columns are nulled directly, which
     * is the only way it now occurs and is exactly how the 133 pre-V12 rows look. Same technique
     * Batch 1 needed to reach a rate table with a sex hole once the coverage rule made it
     * unpublishable.
     */
    @Test
    void aVersionPublishedBeforeTheRuleReadsBackNoFiling() {
        ProductSummaryView product = productApi.createProduct("TERM-B3-LEGACY", "Grandfathered",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, ANY_FILING, "actuary@nlolo.co.tz");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        jdbcTemplate.update("UPDATE product.product_version SET tira_filing_reference = NULL,"
            + " tira_approval_date = NULL WHERE product_version_id = ?", versionId);

        assertThat(productVersionRepository.findById(versionId).orElseThrow().getTiraFiling()).isNull();
    }
```

If `ProductApiIntegrationTest` has no `JdbcTemplate`, add `@Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;` beside the other autowired fields. If Hibernate returns a cached entity after the direct update, add `entityManager.clear()` — or re-read through a fresh `TenantContext` — rather than asserting on the stale instance.

- [ ] **Step 9: Run the product and policy classes**

```
cd backend && ./mvnw test -Dtest=ProductApiIntegrationTest,ProductContractTest,TiraFilingTest,PolicyApiIntegrationTest
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add backend/db-migrations/product/V12__tira_filing.sql backend/src/main backend/src/test
git commit -m "feat(product): a version cannot be published without its TIRA filing" -m "Required on every overload, with no convenience form that defaults it -- that would be a hole exactly where the requirement lives, and it is how mandatory quietly becomes optional in practice. The ~103 existing call sites each supply a shared test fixture." -m "Nullable columns with a NOT VALID check: no filing exists for the 133 versions already in the database, and inventing one would be a fabricated compliance record. Same pattern, and same reasoning, as V5, V8 and V9." -m "This does NOT introduce separation of duties. One ADMIN can still change a live product's price in a single call, now with a reference they typed themselves. The maker-checker finding stays open." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: the filing reads back

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/ProductApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/infrastructure/PublishVersionRequest.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/infrastructure/TiraFilingRequest.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/infrastructure/ProductController.java`
- Modify: `backend/api/openapi/openapi-product.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductContractTest.java`

**Interfaces:**
- Consumes: `TiraFiling`, `ProductVersion.getTiraFiling()`.
- Produces: `VersionRatingView` gains `TiraFiling tiraFiling` as its last component, null for a grandfathered version.

- [ ] **Step 1: Write the failing contract test**

The service-level round trip is already covered by Task 2. This one covers the seam a service test cannot see — Batch 2a shipped `frequencyLoading` missing from `ProductVersionSpec` with every backend test green, and only the console typecheck caught it.

Add to `ProductContractTest`, directly after `sumAssuredBandBoundsSurviveAPublishOverHttp`, whose
structure this mirrors:

```java
    /**
     * The filing has to cross the wire and come back, which is the seam a service-level test
     * cannot see: {@code ProductApiIntegrationTest} constructs {@code TiraFiling} in Java and
     * never touches {@code ProductVersionSpec}. Batch 2a shipped {@code frequencyLoading} present
     * on both response schemas and absent from the REQUEST schema, with every backend test green
     * — the console typecheck was the only thing that caught it.
     *
     * <p>Asserted on the READ, not on the 201: a publish that silently discards half its body
     * still returns 201.
     */
    @Test
    void tiraFilingSurvivesAPublishOverHttpAndIsReadableBack() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String createResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"TIRA-WIRE-01","productName":"Filed Over Http","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(createResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "tiraFiling":{"reference":"TIRA/LIFE/2026/0777","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},
                                    {"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        String snapshot = mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String versionId = JsonPath.read(snapshot, "$.productVersionId");

        mockMvc.perform(get("/products/" + productId + "/versions/" + versionId + "/rating")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tiraFiling.reference").value("TIRA/LIFE/2026/0777"))
            .andExpect(jsonPath("$.tiraFiling.approvalDate").value("2026-01-15"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    /** And a publish with no filing at all is refused at the edge, not deep in the service. */
    @Test
    void aPublishWithNoTiraFilingIsRefusedOverHttp() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String createResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"TIRA-WIRE-02","productName":"Unfiled Over Http","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(createResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},
                                    {"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]}
                    """))
            .andExpect(status().isBadRequest());
    }
```

If `jsonPath` is not already statically imported in this class, add
`import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;`.

- [ ] **Step 2: Run it to verify it fails**

```
cd backend && ./mvnw test -Dtest='ProductContractTest#tiraFilingSurvivesAPublishOverHttpAndIsReadableBack'
```

Expected: FAIL — the request has no `tiraFiling` property and the view returns none.

- [ ] **Step 3: Widen the view**

In `ProductApi`, add to `VersionRatingView`:

```java
                             /**
                              * The filing that authorises this version, or null for one published
                              * before V12. Part of what a reviewer checks, so it reads back here
                              * beside the rating basis and the frequency loading.
                              */
                             TiraFiling tiraFiling) {}
```

In `ProductApiImpl.getVersionRating`, pass `version.getTiraFiling()` as the last argument.

- [ ] **Step 4: Wire the request**

Create `TiraFilingRequest`:

```java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.LocalDate;

/** Mirrors {@code openapi-product.yaml}'s {@code TiraFiling}. Required on every publish. */
public record TiraFilingRequest(
    @NotBlank String reference,
    @NotNull @PastOrPresent LocalDate approvalDate) {}
```

In `PublishVersionRequest`, add as a required component:

```java
    /**
     * The TIRA filing that authorises this version. REQUIRED -- a version may not exist without
     * the filing that authorises it, and there is deliberately no default.
     */
    @NotNull @Valid TiraFilingRequest tiraFiling) {}
```

In `ProductController.publishVersion`, map it before `jwt.getSubject()`:

```java
            new TiraFiling(request.tiraFiling().reference(), request.tiraFiling().approvalDate()),
```

and add `import tz.co.nlolo.lifeplatform.product.api.TiraFiling;`.

- [ ] **Step 5: Extend the contract**

In `openapi-product.yaml`, add `tiraFiling` to `ProductVersionSpec`'s `required` list, and to its properties:

```yaml
        tiraFiling:
          description: >-
            The TIRA filing that authorises this version. Required: in Tanzania a product and
            its rates must be filed with and approved by TIRA before sale, and a version may
            not exist without it. Not verified against TIRA -- this records what a human
            asserts, and it does NOT introduce a second approver.
          type: object
          required: [reference, approvalDate]
          properties:
            reference: { type: string, maxLength: 60 }
            approvalDate: { type: string, format: date }
```

and to `VersionRatingView`'s properties, after `frequencyLoading`:

```yaml
        tiraFiling:
          description: >-
            The filing that authorises this version, or absent for one published before V12.
          type: object
          properties:
            reference: { type: string }
            approvalDate: { type: string, format: date }
```

- [ ] **Step 6: Run the contract and product classes**

```
cd backend && ./mvnw test -Dtest=ProductContractTest,ProductApiIntegrationTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main backend/api/openapi/openapi-product.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductContractTest.java
git commit -m "feat(product): the TIRA filing crosses the wire and reads back" -m "Required on ProductVersionSpec and returned on VersionRatingView. The contract test asserts on the READ rather than the 201, because a publish that silently drops half its body still returns 201 -- which is exactly how frequencyLoading shipped missing from the request schema in batch 2a with every backend test green." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: the console records and shows it

**Files:**
- Modify: `frontend/src/features/products/publishVersionSchema.ts`
- Modify: `frontend/src/features/products/PublishVersionForm.tsx`
- Modify: `frontend/src/features/products/ProductDetailPage.tsx`
- Test: `frontend/src/features/products/publishVersionSchema.test.ts`

**Interfaces:**
- Consumes: the OpenAPI changes from Task 3, via `npm run generate:api`.
- Produces: `tiraReference` and `tiraApprovalDate` on `PublishVersionFormInput`, emitted by `toApiRequest` as a **required** `tiraFiling` object.

- [ ] **Step 1: Regenerate the API types**

```
cd frontend && npm run generate:api
```

`src/types/api/` is gitignored, so there is nothing to commit here — but the types must be regenerated or typecheck will fail against the old shape.

- [ ] **Step 2: Write the failing schema tests**

```ts
  describe('TIRA filing', () => {
    it('refuses a publish with no filing reference', () => {
      const result = termLife.safeParse({ ...valid(), tiraReference: '' });
      expect(result.success).toBe(false);
      expect(JSON.stringify(result.error?.issues)).toContain('TIRA');
    });

    it('refuses an approval date in the future', () => {
      const tomorrow = new Date(Date.now() + 86_400_000).toISOString().slice(0, 10);
      expect(termLife.safeParse({ ...valid(), tiraApprovalDate: tomorrow }).success).toBe(false);
    });

    it('sends the filing an actuary recorded', () => {
      const result = termLife.safeParse(valid());
      expect(result.success).toBe(true);
      expect(toApiRequest(result.data!).tiraFiling).toEqual({
        reference: 'TIRA/LIFE/2026/0001',
        approvalDate: '2026-01-15',
      });
    });
  });
```

and extend the shared `valid()` helper so every existing test still parses — the filing is required, so a fixture without it now fails:

```ts
  const valid = () => ({
    ...blankPublishVersionForm(),
    ifrsMeasurementModel: 'PAA' as const,
    effectiveDate: '2026-01-01',
    retirementDate: '',
    ratingTable: [ageRow, sumRow],
    benefitSchedule: [],
    fundDefinitions: [],
    tiraReference: 'TIRA/LIFE/2026/0001',
    tiraApprovalDate: '2026-01-15',
  });
```

- [ ] **Step 3: Run to verify**

```
cd frontend && npx vitest run src/features/products/publishVersionSchema.test.ts
```

Expected: the three new cases FAIL.

- [ ] **Step 4: Add the fields to the schema**

Beside the eligibility bounds:

```ts
    // The TIRA filing that authorises this version. Required, mirroring the server: an optional
    // compliance field is one nobody fills in.
    tiraReference: z.string().trim().min(1, 'A TIRA filing reference is required'),
    tiraApprovalDate: z
      .string()
      .trim()
      .min(1, 'A TIRA approval date is required')
      .refine((v) => ISO_DATE_PATTERN.test(v), 'Not a valid date')
      .refine(
        (v) => v <= new Date().toISOString().slice(0, 10),
        'An approval that has not happened cannot authorise a product',
      ),
```

Both keys go into `blankPublishVersionForm()` as `''`, and into `toApiRequest` unconditionally — it is required, so there is no omit-when-blank branch:

```ts
    tiraFiling: {
      reference: values.tiraReference,
      approvalDate: values.tiraApprovalDate,
    },
```

- [ ] **Step 5: Add the form fields**

In `PublishVersionForm.tsx`, beside the eligibility grid, matching the surrounding `FormField`/`Input` usage exactly — read the entry-age block and copy its shape:

```tsx
          <FormField label="TIRA filing reference" error={errors.tiraReference?.message}>
            <Input inputSize="sm" placeholder="TIRA/LIFE/2026/0001" {...register('tiraReference')} />
          </FormField>
          <FormField label="TIRA approval date" error={errors.tiraApprovalDate?.message}>
            <Input type="date" inputSize="sm" {...register('tiraApprovalDate')} />
          </FormField>
```

Add both keys to `defaultValues` as `''`.

- [ ] **Step 6: Show it on the detail page**

In `RatingBasis` inside `ProductDetailPage.tsx`, after the instalment-loading section:

```tsx
      <FactorSection
        title="TIRA filing"
        empty="No filing on record — this version was published before filings were required."
        rows={
          rating.data.tiraFiling
            ? [
                {
                  key: 'reference',
                  label: 'Reference',
                  note: null,
                  value: rating.data.tiraFiling.reference ?? '—',
                },
                {
                  key: 'approved',
                  label: 'Approved',
                  note: null,
                  value: rating.data.tiraFiling.approvalDate ?? '—',
                },
              ]
            : []
        }
      />
```

Rendered always, unlike the instalment loading: an absent filing is a fact worth stating, and the empty copy says which versions it applies to rather than leaving a reader to guess.

- [ ] **Step 7: Run the frontend gates**

```
cd frontend && npx vitest run src/features/products src/store/productStore.test.ts ; echo "--- TYPECHECK ---" ; npm run typecheck ; echo "--- LINT ---" ; npm run lint
```

Expected: all green.

- [ ] **Step 8: Commit**

```bash
git add frontend/src
git commit -m "feat(console): a product version records the TIRA filing that authorises it" -m "Two required fields mirroring the server, including the future-date refusal. The detail page always renders the filing section: an absent filing is a fact worth stating, and the empty copy names which versions it applies to rather than leaving a reader to guess." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: full verification

- [ ] **Step 1: Stop the dev backend, then clean-compile**

```
cd backend && ./mvnw clean test-compile
```

Expected: BUILD SUCCESS. `publishVersion` changed shape on four overloads, so this is the step that catches anything the paren-matching script missed.

- [ ] **Step 2: Full backend suite**

```
cd backend && ./mvnw test
```

Expected: BUILD SUCCESS, roughly 1153 + 9 tests. Nothing else may run concurrently.

- [ ] **Step 3: Apply V12 to the dev database and restart**

```
cd backend && docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 < db-migrations/product/V12__tira_filing.sql
```

Then restart the backend and wait for port 8080 to answer.

- [ ] **Step 4: Update the seeder**

`backend/scripts/seed-dev-data.sh` publishes a version over HTTP and will now get a 400. Add to its `POST /products/{id}/versions` body:

```
    "tiraFiling":{"reference":"TIRA/DEMO/0001","approvalDate":"2020-01-01"},
```

A fresh bootstrap is the only thing that exercises this path, and the seeder has silently rotted before — mirror every endpoint tightening into it.

- [ ] **Step 5: Add the fields to the e2e journeys and run them**

Every e2e spec that publishes a version needs the two fields. Find them:

```
cd frontend && grep -rln "Publish version" e2e/
```

For each, after the effective date is filled:

```ts
    await page.getByLabel('TIRA filing reference').fill('TIRA/E2E/0001');
    await page.getByLabel('TIRA approval date').fill(dmy('2026-01-15'));
```

Note `dmy()` — this repo's date inputs are filled through that helper elsewhere in the same spec; use it rather than a raw ISO string.

Then, with backend and frontend up and nothing else running:

```
cd frontend && npx playwright test e2e/staff-products.spec.ts e2e/staff-issue-policy.spec.ts e2e/staff-underwriting.spec.ts
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/scripts/seed-dev-data.sh frontend/e2e
git commit -m "test(product): the seeder and the e2e journeys record a TIRA filing" -m "Publishing now requires one, so every path that publishes needed it -- including the seeder, which only a fresh bootstrap exercises and which has silently rotted before." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

## Done when

- [ ] Full backend suite green after `clean test-compile` then `clean test`, dev backend stopped.
- [ ] Frontend product scope green, plus typecheck and lint.
- [ ] The three e2e specs green against the real stack with V12 applied.
- [ ] `publishVersion` cannot be called without a filing on any overload — verified by there being no overload that omits it, not by a test.

## Deliberately not done

- **Maker-checker.** One ADMIN can still change a live product's price in a single call. This batch adds a reference to that call and nothing more. The audit finding stays open.
- **Verifying the reference.** No TIRA integration, no format validation beyond non-blank.
- **Refusing an effective date before the approval date.** A real compliance failure this makes visible but does not prevent — corrections are republishes carrying the original effective date, and a hard gate with no override produces invented dates. Open item in the spec.
- **A filing document.** The `document` module exists and KYC already attaches evidence; a filing certificate is the obvious attachment and is not in scope.
