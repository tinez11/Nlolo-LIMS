# Credit Life, Plan 2 — Bulk Intake

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A lender's monthly schedule of borrowers becomes members of a credit-life scheme: uploaded, parsed, validated row by row, accepted in part, and answered with a report that says per row whether that borrower is covered.

**Architecture:** An upload creates an `enrolment_submission` in `PENDING` with one `enrolment_submission_row` per line of the file, each already carrying its outcome. Nothing is enrolled yet. A **second** staff user accepts the submission, and only then are the good rows pushed through the `PolicyApi.addMember` that Plan 1 built. Parsing is a pure function over a `Reader`; the only thing the service does with a file is hand it a reader and store the bytes.

**Tech Stack:** Java 21, Spring Boot / Spring Modulith, JPA, Flyway, Postgres 16 with RLS, Apache Commons CSV, MinIO via `DocumentApi`, JUnit 5, Testcontainers.

**Spec:** `../specs/2026-09-21-credit-life-design.md` §3. **Plan 1 is merged** — `AMORTISING_LOAN` schemes, freeform members, loan-keyed enrolment and the above-FCL referral all exist on `main`.

## Global Constraints

- **Money is `NUMERIC(19,2)` in SQL and `BigDecimal` in Java.** Never `double`, never parsed via `Double.parseDouble`.
- **Every new table carries `tenant_id UUID NOT NULL`**, an RLS policy using the `NULLIF` idiom, and explicit `app_role` grants. Follow `policy/V9`'s policies verbatim — this plan adds the first new tables to `policy` since then, and an unprotected table is a cross-tenant leak, not a style lapse.
- **Nothing is enrolled by an upload.** Enrolment happens only on acceptance, by a different user. Spec §2.10.
- **A rejected row means the borrower is NOT COVERED**, and the report says so in those words, per row. Spec §3.
- **Rounding is `HALF_UP` to 2 decimal places.**
- **Run Maven on the host** (`./mvnw`), never in Docker — it breaks Testcontainers networking.
- **Stop the dev backend before any `clean test`**, and re-run `clean test-compile` after any signature change.
- **`NoClassDefFoundError` or "cannot access <some class>" from a build is stale `target/`, not your code.** Seen three times on Plan 1. `clean test-compile` clears it; do not debug it as a failure.

## Two open questions this plan is shaped around

**A — how does cover decline?** Still with the client; it blocks Plan 1 Task 7. It also decided the shape of the template.

**The template is nine columns.** Four have been removed outright rather than made optional,
because neither real lender file carries any of them and a column nobody fills is a column
that rots:

| Removed | Why it is not needed |
|---|---|
| `annual_interest_rate_percent` | Straight-line decline never reads a rate. `AmortisationCalculator.outstandingPrincipalAt` on `FLAT_RATE` computes `principal × (n−k)/n` and ignores it entirely |
| `repayment_frequency` | A lender's product repays on one cadence. It moves to the **scheme**, beside `interest_method` |
| `instalment_amount` | Existed only to infer the interest method, which was withdrawn when the real files turned out to carry no instalment |
| `first_repayment_date` | Derived as disbursement plus one period |

What remains: `loan_account_number`, `borrower_full_name`, `borrower_date_of_birth`,
`borrower_sex`, `borrower_national_id`, `borrower_phone`, `loan_principal_amount`,
`loan_term_months`, `disbursement_date`. Six required — sex, national ID and phone are
optional and always were.

**That is one new column for the lenders, not four.** BUMACO's August sheet already carries
client name, gender, date of birth, disbursed date, disbursed amount and term. The only
thing it lacks is the loan account number, which both lenders have been asked to add and
which stays required: it is the member key, and Plan 1 refuses a borrower without one. A
file lacking that column fails at the header with a message naming it.

**Two consequences to price in, both stated rather than hidden:**

- **A moratorium can no longer be expressed.** `first_repayment_date` was the only way to
  say "this loan has a three-month payment holiday", and every loan is now assumed to
  begin repaying one period after disbursement. Client question 8 asks whether their book
  contains any; if it does, that column comes back.
- **If A comes back "reducing balance", the rate column comes back too.** A reducing
  schedule genuinely needs a per-loan rate. Task 5 keeps the derivation of loan terms in
  one method for exactly this reason.

**B — the committed sample CSVs still show the old thirteen columns.** Task 2 Step 6
updates them.

---

## File structure

| File | Responsibility |
|---|---|
| `db-migrations/policy/V15__enrolment_submission.sql` | Two tables, their RLS, the one-in-flight index and the two-person check |
| `policy/api/EnrolmentRow.java` | One parsed line of a lender's file, typed |
| `policy/api/RowOutcome.java` | `ENROLLED`, `ENROLLED_CAPPED`, `REJECTED` |
| `policy/api/EnrolmentRejection.java` | The closed set of reason codes |
| `policy/domain/EnrolmentCsvParser.java` | Bytes to rows. Pure, no Spring, no database |
| `policy/domain/EnrolmentSubmission.java` | Entity: the submission and its state machine |
| `policy/domain/EnrolmentSubmissionRow.java` | Entity: one row and what happened to it |
| `policy/api/EnrolmentApi.java` | `submit`, `accept`, `withdraw`, `getSubmission`, `renderReport` |
| `policy/application/EnrolmentApiImpl.java` | The service |
| `policy/infrastructure/EnrolmentController.java` | `POST /credit-life-schemes/{n}/enrolments`, accept, report |

Parsing is deliberately separate from the service, the same reasoning as `AmortisationCalculator` and `GroupBenefitCalculator`: it is the part with the most edge cases and the least need for a database.

---

### Task 1: A CSV can reach the application at all

Three separate things block a CSV upload today, and none is in application code.

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/AllowedDocumentContentTypes.java:37`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/document/api/DocumentType.java`
- Create: `backend/db-migrations/document/V3__enrolment_schedule_document_type.sql`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/AllowedDocumentContentTypesTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `text/csv` is an allowed content type; `DocumentType.ENROLMENT_SCHEDULE` exists; `org.apache.commons:commons-csv` is on the classpath.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AllowedDocumentContentTypesTest {

    @Test
    void aCsvIsAllowedBecauseALenderSchedulesArriveAsOne() {
        assertEquals("text/csv", AllowedDocumentContentTypes.normalizeOrThrow("text/csv"));
    }

    @Test
    void parametersAreStillDroppedFromACsvType() {
        // The allowlist exists to guarantee what is persisted is one short literal --
        // text/csv;charset=<script> must not become a stored value.
        assertEquals("text/csv", AllowedDocumentContentTypes.normalizeOrThrow("text/csv;charset=utf-8"));
    }

    @Test
    void anUnknownTypeIsStillRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> AllowedDocumentContentTypes.normalizeOrThrow("application/zip"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AllowedDocumentContentTypesTest`
Expected: FAIL — `application/zip` passes but `text/csv` throws, because the allowlist is four values.

- [ ] **Step 3: Widen the allowlist**

In `AllowedDocumentContentTypes.java`, the `ALLOWED` set becomes:

```java
    public static final Set<String> ALLOWED =
        Set.of("image/jpeg", "image/png", "application/pdf", "application/octet-stream",
            // A lender's enrolment schedule. Text, and parsed rather than stored opaquely,
            // which is why the parser treats every value as untrusted input.
            "text/csv");
```

- [ ] **Step 4: Add the dependency and the multipart limits**

In `backend/pom.xml`, beside the existing dependencies:

```xml
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-csv</artifactId>
            <version>1.11.0</version>
        </dependency>
```

In `application.yml`, under `spring:`:

```yaml
  servlet:
    multipart:
      # Unset means Spring's 1MB default, which a 400-row schedule can exceed and a
      # 5,000-row one certainly does. Capped rather than unlimited: this endpoint is
      # reachable by a counterparty.
      max-file-size: 10MB
      max-request-size: 12MB
```

- [ ] **Step 5: Add the document type**

`DocumentType.java` gains `ENROLMENT_SCHEDULE`. Create
`backend/db-migrations/document/V3__enrolment_schedule_document_type.sql`:

```sql
-- The lender's own file, kept exactly as submitted.
--
-- Stored for the same reason a claim keeps its evidence: when a lender disputes whether
-- a borrower was declared, the answer is the bytes they sent, not our reading of them.

ALTER TABLE document.document_record
    DROP CONSTRAINT IF EXISTS document_record_document_type_check;

ALTER TABLE document.document_record
    ADD CONSTRAINT document_record_document_type_check
    CHECK (document_type IN ('KYC_EVIDENCE','POLICY_DOCUMENT','CLAIM_EVIDENCE','SIGNED_FORM',
                             'UNDERWRITING_EVIDENCE','ENROLMENT_SCHEDULE'));
```

**Verify the constraint name against a real database before writing this**, exactly as
`product/V14` did: `SELECT conname FROM pg_constraint WHERE conrelid =
'document.document_record'::regclass AND contype = 'c';`. `DROP CONSTRAINT IF EXISTS` on a
wrong name silently does nothing and leaves the old five-value check in force.

Also check the bucket routing in `MinioDocumentStorage.java:57-64` — an unrouted type falls
through to `policy-documents`, which is acceptable here and should be stated in a comment
rather than left to be discovered.

- [ ] **Step 6: Run the tests**

Run: `./mvnw clean test-compile && ./mvnw test -Dtest='AllowedDocumentContentTypesTest,DocumentContractTest,ClaimsContractTest'`
Expected: PASS. The last two are the existing upload paths, which share this allowlist.

- [ ] **Step 7: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml \
        backend/src/main/java/tz/co/nlolo/lifeplatform/AllowedDocumentContentTypes.java \
        backend/src/main/java/tz/co/nlolo/lifeplatform/document/api/DocumentType.java \
        backend/db-migrations/document/V3__enrolment_schedule_document_type.sql \
        backend/src/test/java/tz/co/nlolo/lifeplatform/AllowedDocumentContentTypesTest.java
git commit -m "feat(document): a lender's schedule is a document type this platform accepts"
```

---

### Task 2: The parser

The part with all the edge cases and none of the database. Pure, like
`AmortisationCalculator`.

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/EnrolmentRow.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/EnrolmentRejection.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/domain/EnrolmentCsvParser.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/EnrolmentCsvParserTest.java`
- Modify: `backend/docs/superpowers/specs/credit-life-enrolment-sample.csv` and `credit-life-rejection-report-sample.csv`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum EnrolmentRejection { MISSING_REQUIRED_FIELD, MALFORMED_VALUE, DUPLICATE_LOAN_ACCOUNT_NUMBER, DISBURSEMENT_DATE_IN_FUTURE, ENTRY_AGE_OR_TERM_OUT_OF_BOUNDS, LOAN_BEFORE_SCHEME_COMMENCED, ALREADY_ENROLLED }`
  - `record EnrolmentRow(int lineNumber, String loanAccountNumber, String borrowerFullName, LocalDate borrowerDateOfBirth, String borrowerSex, String borrowerNationalId, String borrowerPhone, BigDecimal loanPrincipalAmount, Integer loanTermMonths, LocalDate disbursementDate)`
  - `record RowError(int lineNumber, String loanAccountNumber, String borrowerFullName, EnrolmentRejection reason, String detail)`
  - `record ParsedSchedule(List<EnrolmentRow> rows, List<RowError> errors)`
  - `EnrolmentCsvParser.parse(Reader) -> ParsedSchedule`
  - `EnrolmentCsvParser.MalformedScheduleException extends IllegalArgumentException` — a whole-file problem, not a row problem

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.policy;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRejection;
import tz.co.nlolo.lifeplatform.policy.api.RepaymentFrequency;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentCsvParser;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every value here arrives from a counterparty's spreadsheet export, so the parser is
 * tested as a pure function over strings with no database and no Spring -- the same
 * reasoning as AmortisationCalculatorTest.
 */
class EnrolmentCsvParserTest {

    private static final String HEADER =
        "loan_account_number,borrower_full_name,borrower_date_of_birth,borrower_sex,"
        + "borrower_national_id,borrower_phone,loan_principal_amount,"
        + "loan_term_months,disbursement_date\n";

    private static EnrolmentCsvParser.ParsedSchedule parse(String body) {
        return EnrolmentCsvParser.parse(new StringReader(HEADER + body));
    }

    @Test
    void aCompleteRowParsesIntoTypedValues() {
        var parsed = parse("LN-2026-00417,Amina Hassan Mwinyi,1988-03-14,F,,,"
            + "8500000.00,48,2026-08-03\n");

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(1);
        var row = parsed.rows().get(0);
        assertThat(row.loanAccountNumber()).isEqualTo("LN-2026-00417");
        assertThat(row.borrowerFullName()).isEqualTo("Amina Hassan Mwinyi");
        assertThat(row.borrowerDateOfBirth()).isEqualTo(LocalDate.of(1988, 3, 14));
        assertThat(row.loanPrincipalAmount()).isEqualByComparingTo("8500000.00");
        assertThat(row.loanTermMonths()).isEqualTo(48);
        assertThat(row.disbursementDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(row.lineNumber()).isEqualTo(2);
    }

    @Test
    void theThreeIdentityColumnsMayBeBlank() {
        // Sex, national ID and phone are optional and always were. BUMACO sends gender;
        // neither lender sends a national ID, which is why party de-duplication cannot
        // fire on a borrower and the loan account number keys the member instead.
        var parsed = parse("LN-2026-00418,Joseph Mkenda,1975-11-02,,,,24000000.00,60,2026-08-05\n");

        assertThat(parsed.errors()).isEmpty();
        var row = parsed.rows().get(0);
        assertThat(row.borrowerSex()).isNull();
        assertThat(row.borrowerNationalId()).isNull();
        assertThat(row.borrowerPhone()).isNull();
        assertThat(row.loanPrincipalAmount()).isEqualByComparingTo("24000000.00");
    }

    @Test
    void aMissingRequiredFieldIsARowErrorAndNotAFileError() {
        // One bad row must not cost 399 people their cover.
        var parsed = parse("LN-A,Good Borrower,1990-01-01,F,,,1000000.00,12,2026-08-01\n"
            + "LN-B,,1991-05-23,F,,,5000000.00,36,2026-08-17\n");

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.errors()).hasSize(1);
        assertThat(parsed.errors().get(0).reason())
            .isEqualTo(EnrolmentRejection.MISSING_REQUIRED_FIELD);
        assertThat(parsed.errors().get(0).detail()).contains("borrower_full_name");
        assertThat(parsed.errors().get(0).lineNumber()).isEqualTo(3);
    }

    @Test
    void anUnparseableAmountNamesTheColumnAndTheValue() {
        var parsed = parse("LN-C,Bad Amount,1990-01-01,F,,,8.5E+06,12,2026-08-01\n");

        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.errors().get(0).reason()).isEqualTo(EnrolmentRejection.MALFORMED_VALUE);
        assertThat(parsed.errors().get(0).detail())
            .contains("loan_principal_amount").contains("8.5E+06");
    }

    @Test
    void anExcelSerialDateIsRefusedRatherThanRead() {
        // 46203 is 2026-06-30 to Excel and nothing at all to LocalDate. Reading it as a
        // year would insure the wrong person for the wrong term, so it is refused by
        // name -- and the message is what tells the lender their export is wrong.
        var parsed = parse("LN-SERIAL,Amina,34237,F,,,1000000.00,12,46203\n");

        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.errors().get(0).reason()).isEqualTo(EnrolmentRejection.MALFORMED_VALUE);
        assertThat(parsed.errors().get(0).detail())
            .contains("borrower_date_of_birth").contains("34237");
    }

    @Test
    void aDuplicateLoanAccountNumberWithinOneFileIsCaughtHere() {
        // The database index catches it across files; within one file the second row must
        // be named, because "already an active member" would be a confusing answer to a
        // row the lender sent twice by mistake.
        var parsed = parse("LN-DUP,First,1990-01-01,F,,,1000000.00,12,2026-08-01\n"
            + "LN-DUP,Second,1991-01-01,M,,,2000000.00,12,2026-08-01\n");

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.errors().get(0).reason())
            .isEqualTo(EnrolmentRejection.DUPLICATE_LOAN_ACCOUNT_NUMBER);
        assertThat(parsed.errors().get(0).detail()).contains("line 2");
    }

    @Test
    void aMissingRequiredColumnFailsTheWholeFile() {
        // A header problem is not a row problem: every row would fail identically, and
        // 400 identical rejections tell the lender less than one sentence does.
        assertThatThrownBy(() -> EnrolmentCsvParser.parse(new StringReader(
            "borrower_full_name,loan_principal_amount\nAmina,8500000.00\n")))
            .isInstanceOf(EnrolmentCsvParser.MalformedScheduleException.class)
            .hasMessageContaining("loan_account_number");
    }

    @Test
    void aByteOrderMarkDoesNotHideTheFirstColumn() {
        // Excel writes UTF-8 with a BOM. Without stripping it the first header reads
        // "﻿loan_account_number" and the file fails as if the column were absent --
        // which is exactly the confusing failure this test exists to prevent.
        var parsed = EnrolmentCsvParser.parse(new StringReader(
            "﻿" + HEADER + "LN-BOM,Amina,1990-01-01,F,,,1000000.00,12,2026-08-01\n"));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows().get(0).loanAccountNumber()).isEqualTo("LN-BOM");
    }

    @Test
    void headersAreMatchedCaseAndSpaceInsensitively() {
        var parsed = EnrolmentCsvParser.parse(new StringReader(
            " Loan_Account_Number ,Borrower_Full_Name,BORROWER_DATE_OF_BIRTH,borrower_sex,"
            + "borrower_national_id,borrower_phone,loan_principal_amount,"
            + "loan_term_months,disbursement_date\n"
            + "LN-CASE,Amina,1990-01-01,F,,,1000000.00,12,2026-08-01\n"));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows().get(0).loanAccountNumber()).isEqualTo("LN-CASE");
    }

    @Test
    void anExtraColumnTheTemplateDoesNotKnowIsIgnored() {
        // Both real lender files carry columns we do not use -- S/N, AGE, premium per
        // policy year. Refusing a file for carrying more than we asked for would reject
        // every real export on day one.
        var parsed = EnrolmentCsvParser.parse(new StringReader(
            "s_no,loan_account_number,borrower_full_name,borrower_date_of_birth,borrower_sex,"
            + "borrower_national_id,borrower_phone,loan_principal_amount,"
            + "loan_term_months,disbursement_date,age,total_premium\n"
            + "1,LN-EXTRA,Amina,1990-01-01,F,,,1000000.00,12,2026-08-01,36,52000.00\n"));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows().get(0).loanAccountNumber()).isEqualTo("LN-EXTRA");
    }

    @Test
    void trailingBlankRowsAreSkippedRatherThanRejected() {
        // The real LOLC sheet carries ~200 trailing rows of formula residue below the
        // data. Rejecting them would bury 8 real rejections under 200 noise ones.
        var parsed = parse("LN-REAL,Amina,1990-01-01,F,,,1000000.00,12,2026-08-01\n"
            + ",,,,,,,,\n,,,,,,,,\n");

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.errors()).isEmpty();
    }

    @Test
    void anEmptyFileIsAFileError() {
        assertThatThrownBy(() -> EnrolmentCsvParser.parse(new StringReader("")))
            .isInstanceOf(EnrolmentCsvParser.MalformedScheduleException.class)
            .hasMessageContaining("no header");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=EnrolmentCsvParserTest`
Expected: FAIL — compilation, nothing exists yet.

- [ ] **Step 3: Write the value types**

`policy/api/EnrolmentRejection.java`:

```java
package tz.co.nlolo.lifeplatform.policy.api;

/**
 * Why a row was refused. A closed set, because these reach a counterparty: a lender
 * fixing a spreadsheet needs to be able to grep their own report, and free text cannot
 * be grepped, counted or translated.
 */
public enum EnrolmentRejection {
    MISSING_REQUIRED_FIELD,
    MALFORMED_VALUE,
    DUPLICATE_LOAN_ACCOUNT_NUMBER,
    DISBURSEMENT_DATE_IN_FUTURE,
    ENTRY_AGE_OR_TERM_OUT_OF_BOUNDS,
    LOAN_BEFORE_SCHEME_COMMENCED,
    ALREADY_ENROLLED
}
```

`policy/api/EnrolmentRow.java` — the 13 columns, typed, with `lineNumber` first so an
error can always name the line a human is looking at. Required: `loanAccountNumber`,
`borrowerFullName`, `borrowerDateOfBirth`, `loanPrincipalAmount`, `loanTermMonths`,
`disbursementDate`. The rest are nullable, and the javadoc must say **why** the three loan
ones are: neither real lender file carries them.

- [ ] **Step 4: Write the parser**

`policy/domain/EnrolmentCsvParser.java`. Shape:

```java
public final class EnrolmentCsvParser {

    public record RowError(int lineNumber, String loanAccountNumber, String borrowerFullName,
                            EnrolmentRejection reason, String detail) {}

    public record ParsedSchedule(List<EnrolmentRow> rows, List<RowError> errors) {}

    /** A problem with the FILE, not with a row: every row would fail identically. */
    public static class MalformedScheduleException extends IllegalArgumentException {
        public MalformedScheduleException(String message) { super(message); }
    }

    /**
     * The six a lender must send. Sex, national ID and phone are optional; anything else
     * in the file is ignored rather than refused -- both real exports carry columns we
     * do not use (S/N, AGE, premium per policy year).
     */
    private static final List<String> REQUIRED_COLUMNS = List.of(
        "loan_account_number", "borrower_full_name", "borrower_date_of_birth",
        "loan_principal_amount", "loan_term_months", "disbursement_date");

    public static ParsedSchedule parse(Reader reader) { ... }
}
```

Implementation notes that are requirements, not suggestions:

- **Strip a leading `﻿`** before handing the reader to Commons CSV.
- Build the format with `CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreSurroundingSpaces(true).setTrim(true).build()`, then normalise each header yourself with `toLowerCase(Locale.ROOT).strip()` — `setIgnoreHeaderCase` alone does not handle the spaces Excel leaves behind.
- **Amounts through `new BigDecimal(String)`**, never `Double.parseDouble`. Reject scientific notation explicitly: a numeric-looking account number or a large principal exported by Excel arrives as `8.5E+06`, and `BigDecimal` would *accept* it silently. Check the raw string with `value.indexOf('E') >= 0 || value.indexOf('e') >= 0` and raise `MALFORMED_VALUE` naming the value.
- **Dates through `LocalDate.parse`** (ISO only). An Excel serial such as `46203` is not a date; it parses as nothing and must be `MALFORMED_VALUE` naming the column, because silently reading it as a year would insure the wrong person for the wrong term.
- **A row where every cell is blank is skipped**, not rejected — the real LOLC sheet has ~200 of them below the data.
- **Duplicates within the file** are detected with a `Map<String,Integer>` of account number to first line, and the detail names the earlier line.
- **Unknown columns are ignored, not refused.** Both real exports carry `S/N`, `AGE` and per-policy-year premium columns. Refusing a file for carrying more than we asked would reject every real export on day one.
- **One error per row, the first one found.** A lender fixing a row fixes it whole; five findings on one line is noise.

- [ ] **Step 5: Run the tests**

Run: `./mvnw test -Dtest=EnrolmentCsvParserTest`
Expected: PASS, all 11.

- [ ] **Step 6: Bring the sample files and the client documents in line**

The committed samples still show thirteen columns. Cut
`credit-life-enrolment-sample.csv` to the nine, leaving `borrower_sex`,
`borrower_national_id` and `borrower_phone` blank on some rows because that is what a real
lender file looks like. Update `credit-life-rejection-report-sample.csv` to the reason
codes in `EnrolmentRejection` — its `INSTALMENT_MATCHES_NO_KNOWN_METHOD` row no longer
exists and should become the Excel-serial case, which is the failure their actual export
will produce.

**Then update the two documents that describe the template to the client**:

- `../specs/2026-09-21-credit-life-design.md` §3 lists thirteen columns in a table and
  explains `first_repayment_date` as the way a moratorium is expressed. Both are now
  wrong. Record that four columns were removed and that a moratorium can no longer be
  stated.
- `../specs/2026-09-21-credit-life-client-questions.md` opens by asking for ten real rows
  in the template. It should now say plainly that the ask is **one new column** — the loan
  account number — because BUMACO's sheet already carries the other eight. That is a much
  easier thing for the client to say yes to, and worth saying in those words.

- [ ] **Step 7: Negative control**

Temporarily delete the BOM strip and re-run. Expected: `aByteOrderMarkDoesNotHideTheFirstColumn`
fails with a message about `loan_account_number` being absent — which is precisely the
misleading failure the strip prevents. Restore, confirm green.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/EnrolmentRow.java \
        backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/EnrolmentRejection.java \
        backend/src/main/java/tz/co/nlolo/lifeplatform/policy/domain/EnrolmentCsvParser.java \
        backend/src/test/java/tz/co/nlolo/lifeplatform/policy/EnrolmentCsvParserTest.java \
        backend/docs/superpowers/specs/
git commit -m "feat(policy): read a lender's schedule without trusting a single cell of it"
```

---

### Task 3: The submission tables

**Files:**
- Create: `backend/db-migrations/policy/V15__enrolment_submission.sql`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/domain/EnrolmentSubmission.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/domain/EnrolmentSubmissionRow.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/RowOutcome.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/SubmissionStatus.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/EnrolmentSubmissionRepository.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/EnrolmentSubmissionRowRepository.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/EnrolmentSubmissionConstraintTest.java`

**Interfaces:**
- Consumes: `EnrolmentRejection` (Task 2).
- Produces: `enum SubmissionStatus { PENDING, ACCEPTED, WITHDRAWN }`; `enum RowOutcome { ENROLLED, ENROLLED_CAPPED, REJECTED }`; the two entities and their repositories.

- [ ] **Step 1: Write the failing test**

A Testcontainers class in the style of `GroupSchemeIntegrationTest`, asserting the three
constraints by the constraint name in the failure — a migration that merely applies proves
nothing about whether its constraints refuse anything:

```java
@Test
void onlyOneSubmissionMayBeInFlightPerScheme() {
    insertPendingSubmission(tenantId, policyNumber, "staff.one");
    assertThatThrownBy(() -> insertPendingSubmission(tenantId, policyNumber, "staff.two"))
        .hasMessageContaining("ux_enrolment_submission_in_flight");
}

@Test
void anAcceptedSubmissionFreesTheScheme() {
    UUID first = insertPendingSubmission(tenantId, policyNumber, "staff.one");
    jdbcTemplate.update("update policy.enrolment_submission set status='ACCEPTED', "
        + "accepted_by='staff.two', accepted_at=now() where submission_id=?", first);
    // The index is partial on PENDING, so the next file is not blocked by history.
    assertThatCode(() -> insertPendingSubmission(tenantId, policyNumber, "staff.three"))
        .doesNotThrowAnyException();
}

@Test
void theSamePersonCannotAcceptWhatTheySubmitted() {
    UUID id = insertPendingSubmission(tenantId, policyNumber, "staff.one");
    assertThatThrownBy(() -> jdbcTemplate.update(
        "update policy.enrolment_submission set status='ACCEPTED', accepted_by='staff.one', "
        + "accepted_at=now() where submission_id=?", id))
        .hasMessageContaining("chk_enrolment_submission_two_person");
}

@Test
void anAcceptedSubmissionMustSayWhoAcceptedItAndWhen() {
    UUID id = insertPendingSubmission(tenantId, policyNumber, "staff.one");
    assertThatThrownBy(() -> jdbcTemplate.update(
        "update policy.enrolment_submission set status='ACCEPTED' where submission_id=?", id))
        .hasMessageContaining("chk_enrolment_submission_accepted_complete");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=EnrolmentSubmissionConstraintTest`
Expected: FAIL — `relation "policy.enrolment_submission" does not exist`.

- [ ] **Step 3: Write the migration**

`backend/db-migrations/policy/V15__enrolment_submission.sql`:

```sql
-- A lender's monthly schedule, as submitted and as judged.
--
-- Nothing here enrols anybody. A submission is a PROPOSAL: the rows are parsed, judged
-- and recorded, and a SECOND staff user turns it into cover. Spec §2.10 -- a counterparty
-- with a direct financial interest in maximising cover must not be able to create the
-- insurer's liability unattended, and backdating cover to disbursement means the wait
-- costs throughput rather than risk.

-- Repayment cadence moves from the file to the scheme. A lender's product repays on one
-- cadence, so asking for it on every one of 400 rows is 400 chances to disagree with
-- itself. It sits beside interest_method for the same reason and with the same rule: no
-- setter, because every member already enrolled was valued against the old answer.
--
-- Defaulted to MONTHLY and backfilled, because every scheme that exists today is monthly
-- and a NOT NULL column with no default cannot be added to a populated table.
ALTER TABLE policy.group_scheme
    ADD COLUMN repayment_frequency VARCHAR(20)
        CHECK (repayment_frequency IS NULL
               OR repayment_frequency IN ('MONTHLY','QUARTERLY'));

UPDATE policy.group_scheme SET repayment_frequency = 'MONTHLY'
 WHERE benefit_basis = 'AMORTISING_LOAN' AND repayment_frequency IS NULL;

CREATE TABLE policy.enrolment_submission (
    submission_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL,
    policy_number    VARCHAR(20) NOT NULL REFERENCES policy.group_scheme(policy_number),

    -- The lender's own file, byte for byte. When a lender disputes whether a borrower was
    -- declared, the answer is what they sent, not our reading of it.
    document_ref     VARCHAR(100) NOT NULL,
    file_name        VARCHAR(255),

    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','ACCEPTED','WITHDRAWN')),


    row_count        INTEGER NOT NULL CHECK (row_count >= 0),
    enrolled_count   INTEGER NOT NULL DEFAULT 0 CHECK (enrolled_count >= 0),
    rejected_count   INTEGER NOT NULL DEFAULT 0 CHECK (rejected_count >= 0),

    submitted_by     VARCHAR(100) NOT NULL,
    submitted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_by      VARCHAR(100),
    accepted_at      TIMESTAMPTZ,

    -- Two people, and they must be different. A single user who can upload and accept
    -- their own file has an audit trail and no control. Spec §2.10, and the platform
    -- already carries one open finding of that shape on pricing.
    CONSTRAINT chk_enrolment_submission_two_person CHECK (
        accepted_by IS NULL OR accepted_by <> submitted_by),

    CONSTRAINT chk_enrolment_submission_accepted_complete CHECK (
        (status <> 'ACCEPTED') OR (accepted_by IS NOT NULL AND accepted_at IS NOT NULL))
);

-- One file in flight per scheme. Two concurrent submissions can enrol the same loan
-- twice, and propose-then-accept widens the window between reading and writing.
-- Partial on PENDING, so accepted history never blocks next month's file.
CREATE UNIQUE INDEX ux_enrolment_submission_in_flight
    ON policy.enrolment_submission (policy_number)
    WHERE status = 'PENDING';

CREATE INDEX idx_enrolment_submission_scheme
    ON policy.enrolment_submission (tenant_id, policy_number, submitted_at DESC);

CREATE TABLE policy.enrolment_submission_row (
    submission_row_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    submission_id       UUID NOT NULL REFERENCES policy.enrolment_submission(submission_id),

    -- The line in the lender's own file, so a report can be read beside the spreadsheet
    -- that produced it. 1 is the header; data starts at 2.
    line_number         INTEGER NOT NULL CHECK (line_number > 1),

    loan_account_number VARCHAR(50),
    borrower_full_name  VARCHAR(200),

    outcome             VARCHAR(20) NOT NULL
        CHECK (outcome IN ('ENROLLED','ENROLLED_CAPPED','REJECTED')),
    reason_code         VARCHAR(40),
    reason              VARCHAR(500),

    -- Set only once the row is actually enrolled, which is at acceptance.
    policy_member_id    UUID,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A rejection without a reason is a row nobody can act on, and the reason is the
    -- whole product of this feature.
    CONSTRAINT chk_enrolment_row_rejection_has_reason CHECK (
        (outcome <> 'REJECTED') OR (reason_code IS NOT NULL AND reason IS NOT NULL)),

    UNIQUE (submission_id, line_number)
);

CREATE INDEX idx_enrolment_row_submission
    ON policy.enrolment_submission_row (tenant_id, submission_id, line_number);

-- RLS, copied from policy/V9's pattern. NULLIF, not a bare cast: a RESET GUC raises
-- rather than returning null, and the predicate must fail closed to zero rows.
ALTER TABLE policy.enrolment_submission ENABLE ROW LEVEL SECURITY;
ALTER TABLE policy.enrolment_submission FORCE ROW LEVEL SECURITY;
CREATE POLICY enrolment_submission_tenant_isolation ON policy.enrolment_submission
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE policy.enrolment_submission_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE policy.enrolment_submission_row FORCE ROW LEVEL SECURITY;
CREATE POLICY enrolment_submission_row_tenant_isolation ON policy.enrolment_submission_row
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON policy.enrolment_submission TO app_role;
GRANT SELECT, INSERT, UPDATE ON policy.enrolment_submission_row TO app_role;
```

**Check the exact RLS and GRANT wording against `policy/V9` and `policy/V12` before
writing this** — `V12` is the fail-closed pass and its predicate form is the one to copy.
`document_ref VARCHAR(100)`: confirm against a real `document_record.document_ref` value
before trusting it, per the platform's width-check convention.

- [ ] **Step 4: Write the entities and repositories**

**First, `GroupScheme` gains the cadence the migration just added** — Task 5 reads it:

```java
    /**
     * How often this lender's loans repay. Null on every basis but AMORTISING_LOAN.
     *
     * <p>No setter, for the same reason interestMethod has none: every member already on
     * the roll had their schedule counted against the old answer.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "repayment_frequency")
    private RepaymentFrequency repaymentFrequency;

    public RepaymentFrequency getRepaymentFrequency() { return repaymentFrequency; }
```

It joins the constructor beside `interestMethod`, is **required on `AMORTISING_LOAN` and
rejected on every other basis** (mirror the existing `interestMethod` validation exactly,
including the check in `PolicyApiImpl.issueGroupScheme` that runs before the opening
schedule is valued), and joins `IssueGroupSchemeRequest` — whose existing 18-argument
convenience constructor keeps the three employer bases from having to pass two nulls that
mean nothing to them. Extend `aCreditLifeSchemeMustStateHowItsLoansRepay` in
`GroupSchemeIntegrationTest` with the cadence equivalent.

**Then** `EnrolmentSubmission`, which carries its state machine as methods, not setters:
`accept(String acceptedBy)` refuses when already accepted, refuses when
`acceptedBy.equals(submittedBy)` with a message saying so, and refuses a `WITHDRAWN`
submission; `withdraw()` refuses anything but `PENDING`. The database constraints are the
guarantee; these are the readable errors.

- [ ] **Step 5: Run the tests**

Run: `./mvnw clean test-compile && ./mvnw test -Dtest=EnrolmentSubmissionConstraintTest`
Expected: PASS, all four.

- [ ] **Step 6: Commit**

```bash
git add backend/db-migrations/policy/V15__enrolment_submission.sql \
        backend/src/main/java/tz/co/nlolo/lifeplatform/policy/ \
        backend/src/test/java/tz/co/nlolo/lifeplatform/policy/EnrolmentSubmissionConstraintTest.java
git commit -m "feat(policy): a submitted schedule is a proposal, not an enrolment"
```

---

### Task 4: Submit a file

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/EnrolmentApi.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/EnrolmentSubmissionView.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/EnrolmentRowView.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/EnrolmentApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/EnrolmentIntegrationTest.java`

**Interfaces:**
- Consumes: `EnrolmentCsvParser.parse` (Task 2); the entities (Task 3); `PolicyApi.getGroupScheme`; `DocumentApi.upload`.
- Produces:
  - `EnrolmentApi.submit(String policyNumber, InputStream file, long size, String contentType, String fileName, String submittedBy) -> EnrolmentSubmissionView`
  - `record EnrolmentSubmissionView(UUID submissionId, String policyNumber, SubmissionStatus status, int rowCount, int enrolledCount, int rejectedCount, String submittedBy, Instant submittedAt, String acceptedBy, Instant acceptedAt)`
  - `record EnrolmentRowView(int lineNumber, String loanAccountNumber, String borrowerFullName, RowOutcome outcome, EnrolmentRejection reasonCode, String reason, UUID policyMemberId)`

**Every row is judged at submit, and nothing is written to the schedule.** The judgement
must therefore be made without `addMember`'s side effects: age and term against the
product's bounds, disbursement against today and against commencement, the loan account
number against members already active on the scheme.

- [ ] **Step 1: Write the failing test**

```java
@Test
void aFileIsJudgedRowByRowAndNobodyIsEnrolledYet() {
    var submission = enrolmentApi.submit(creditLifeScheme, csv(
        "LN-2026-00417,Amina Hassan Mwinyi,1988-03-14,F,,,8500000.00,,48,,,2026-06-30,\n"
        + "LN-2026-00418,,1975-11-02,M,,,24000000.00,,60,,,2026-07-05,\n"),
        "june.csv", "staff.one");

    assertThat(submission.status()).isEqualTo(SubmissionStatus.PENDING);
    assertThat(submission.rowCount()).isEqualTo(2);
    assertThat(submission.rejectedCount()).isEqualTo(1);

    // The scheme has ONLY its opening member: judging is not enrolling.
    assertThat(policyApi.getGroupScheme(creditLifeScheme).activeMemberCount()).isEqualTo(1);
}

@Test
void aRejectedRowSaysTheBorrowerIsNotCovered() {
    var submission = enrolmentApi.submit(creditLifeScheme, csv(
        "LN-BAD,,1975-11-02,M,,,24000000.00,,60,,,2026-07-05,\n"), "bad.csv", "staff.one");

    var row = enrolmentApi.listRows(submission.submissionId()).get(0);
    assertThat(row.outcome()).isEqualTo(RowOutcome.REJECTED);
    assertThat(row.reasonCode()).isEqualTo(EnrolmentRejection.MISSING_REQUIRED_FIELD);
    assertThat(row.reason())
        .as("the one sentence in this system that has to be unmistakable")
        .contains("THIS BORROWER IS NOT COVERED");
}

@Test
void aBorrowerAlreadyOnTheSchemeIsRejectedNotDoubled() {
    enrolmentApiAcceptedSubmissionWith("LN-2026-00417");
    var second = enrolmentApi.submit(creditLifeScheme, csv(
        "LN-2026-00417,Amina Hassan Mwinyi,1988-03-14,F,,,8500000.00,,48,,,2026-06-30,\n"),
        "again.csv", "staff.one");

    assertThat(enrolmentApi.listRows(second.submissionId()).get(0).reasonCode())
        .isEqualTo(EnrolmentRejection.ALREADY_ENROLLED);
}

@Test
void aSecondFileCannotBeSubmittedWhileOneIsPending() {
    enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW), "first.csv", "staff.one");
    assertThatThrownBy(() -> enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW),
            "second.csv", "staff.one"))
        .isInstanceOf(InvalidPolicyStateException.class)
        .hasMessageContaining("already has a submission awaiting acceptance");
}

@Test
void theLendersOwnFileIsKeptExactlyAsSubmitted() {
    var submission = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW), "june.csv", "staff.one");
    // Disputes are settled by the bytes the lender sent, not by our reading of them.
    assertThat(enrolmentApi.getSubmission(submission.submissionId()).submissionId()).isNotNull();
    assertThat(documentApi.listByOwnerContext("submission:" + submission.submissionId()))
        .hasSize(1);
}

@Test
void aFileWithNoLoanAccountNumberColumnIsRefusedWhole() {
    assertThatThrownBy(() -> enrolmentApi.submit(creditLifeScheme,
            "borrower_full_name,loan_principal_amount\nAmina,8500000.00\n",
            "wrong.csv", "staff.one"))
        .isInstanceOf(InvalidPolicyStateException.class)
        .hasMessageContaining("loan_account_number");
}

@Test
void aSchemeThatIsNotCreditLifeRefusesAFileEntirely() {
    assertThatThrownBy(() -> enrolmentApi.submit(employerScheme, csv(ONE_GOOD_ROW),
            "wrong.csv", "staff.one"))
        .isInstanceOf(InvalidPolicyStateException.class)
        .hasMessageContaining("AMORTISING_LOAN");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=EnrolmentIntegrationTest`
Expected: FAIL — compilation, `EnrolmentApi` does not exist.

- [ ] **Step 3: Write the service**

`submit` in order:

1. Resolve the scheme; refuse anything whose basis is not `AMORTISING_LOAN`, naming the basis.
2. Refuse when a `PENDING` submission already exists for the scheme — a readable error in front of `ux_enrolment_submission_in_flight`, which stays the guarantee.
3. Read the bytes ONCE into a `byte[]`: the file is both stored and parsed, and an `InputStream` cannot be consumed twice. Cap it against `max-file-size` so a stream lying about its length cannot exhaust the heap.
4. `documentApi.upload("submission:" + submissionId, DocumentType.ENROLMENT_SCHEDULE, submittedBy, ...)`. **Width check:** `owner_context` is `VARCHAR(50)` and `"submission:" + uuid` is 47 characters — it fits, with three to spare, and `document/V2`'s own note says to verify rather than estimate.
5. Parse. A `MalformedScheduleException` becomes an `InvalidPolicyStateException` carrying its message: the file is refused whole and no submission row is written.
6. Judge every parsed row (below), write one `enrolment_submission_row` each, and the counts.

Judging, beyond what the parser already caught:

```java
// Ages and terms against the product's OWN bounds, not against a constant. The gates are
// hard: a 25-year loan truncated to the product's 10-year maximum is a lender believing
// they are covered for fifteen years they are not.
EligibilityBounds bounds = productApi.getActiveSnapshot(...).eligibilityBounds();
```

- `DISBURSEMENT_DATE_IN_FUTURE` when after today.
- `LOAN_BEFORE_SCHEME_COMMENCED` when before the scheme's commencement.
- `ENTRY_AGE_OR_TERM_OUT_OF_BOUNDS` when entry age or maturity age falls outside the product's bounds, or the term does — the message quotes the computed age and the bound.
- `ALREADY_ENROLLED` when the loan account number is already `ACTIVE` on this scheme.
- Otherwise `ENROLLED`, or `ENROLLED_CAPPED` when the principal exceeds the scheme's FCL, with the reason naming the limit, the excess and the fact that a referral will open on acceptance.

**Every `REJECTED` reason ends with the literal `THIS BORROWER IS NOT COVERED.`** Put it in
one constant so it cannot drift between reason codes.

- [ ] **Step 4: Run tests** — `./mvnw test -Dtest=EnrolmentIntegrationTest`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy/ \
        backend/src/test/java/tz/co/nlolo/lifeplatform/policy/EnrolmentIntegrationTest.java
git commit -m "feat(policy): a lender's file is judged row by row, and enrols nobody"
```

---

### Task 5: Accept a submission

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/EnrolmentApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/EnrolmentApiImpl.java`
- Test: extend `EnrolmentIntegrationTest`

**Interfaces:**
- Consumes: Task 4's submission; `PolicyApi.addMember`.
- Produces: `EnrolmentApi.accept(UUID submissionId, String acceptedBy) -> EnrolmentSubmissionView`; `EnrolmentApi.withdraw(UUID submissionId, String withdrawnBy) -> EnrolmentSubmissionView`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void acceptanceEnrolsTheGoodRowsAndOnlyThose() {
    var submission = enrolmentApi.submit(creditLifeScheme, csv(
        "LN-A,Amina Hassan Mwinyi,1988-03-14,F,,,8500000.00,,48,,,2026-06-30,\n"
        + "LN-B,,1975-11-02,M,,,24000000.00,,60,,,2026-07-05,\n"
        + "LN-C,Grace Shirima,1992-06-21,F,,,3200000.00,,24,,,2026-07-06,\n"),
        "june.csv", "staff.one");

    var accepted = enrolmentApi.accept(submission.submissionId(), "staff.two");

    assertThat(accepted.status()).isEqualTo(SubmissionStatus.ACCEPTED);
    assertThat(accepted.enrolledCount()).isEqualTo(2);
    // 1 opening member + 2 accepted rows. The rejected row enrolled nobody.
    assertThat(policyApi.getGroupScheme(creditLifeScheme).activeMemberCount()).isEqualTo(3);
    assertThat(policyApi.getGroupScheme(creditLifeScheme).totalCoveredAmount())
        .isEqualByComparingTo("22100000.00"); // 10,400,000 + 8,500,000 + 3,200,000
}

@Test
void theUploaderCannotAcceptTheirOwnFile() {
    var submission = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW), "june.csv", "staff.one");

    assertThatThrownBy(() -> enrolmentApi.accept(submission.submissionId(), "staff.one"))
        .isInstanceOf(InvalidPolicyStateException.class)
        .hasMessageContaining("cannot accept a submission they uploaded");
}

@Test
void anAcceptedRowRemembersTheMemberItBecame() {
    var submission = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW), "june.csv", "staff.one");
    enrolmentApi.accept(submission.submissionId(), "staff.two");

    // Without this a report cannot answer "which member is this row?" six months later.
    assertThat(enrolmentApi.listRows(submission.submissionId()).get(0).policyMemberId()).isNotNull();
}

@Test
void acceptingTwiceIsRefused() {
    var submission = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW), "june.csv", "staff.one");
    enrolmentApi.accept(submission.submissionId(), "staff.two");

    assertThatThrownBy(() -> enrolmentApi.accept(submission.submissionId(), "staff.three"))
        .isInstanceOf(InvalidPolicyStateException.class)
        .hasMessageContaining("already accepted");
}

@Test
void anAcceptedFileFreesTheSchemeForNextMonth() {
    var june = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW), "june.csv", "staff.one");
    enrolmentApi.accept(june.submissionId(), "staff.two");

    assertThatCode(() -> enrolmentApi.submit(creditLifeScheme,
        csv("LN-JULY,Joseph Mkenda,1975-11-02,M,,,2400000.00,,24,,,2026-07-31,\n"),
        "july.csv", "staff.one")).doesNotThrowAnyException();
}

@Test
void withdrawingAPendingFileEnrolsNobodyAndFreesTheScheme() {
    var submission = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW), "june.csv", "staff.one");
    enrolmentApi.withdraw(submission.submissionId(), "staff.one");

    assertThat(policyApi.getGroupScheme(creditLifeScheme).activeMemberCount()).isEqualTo(1);
    assertThatCode(() -> enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW),
        "corrected.csv", "staff.one")).doesNotThrowAnyException();
}

@Test
void anAboveFclRowIsEnrolledCappedAndCarriesItsReferral() {
    var submission = enrolmentApi.submit(creditLifeScheme, csv(
        "LN-BIG,Peter Massawe,1980-07-19,M,,,30000000.00,,72,,,2026-07-13,\n"),
        "big.csv", "staff.one");
    assertThat(enrolmentApi.listRows(submission.submissionId()).get(0).outcome())
        .isEqualTo(RowOutcome.ENROLLED_CAPPED);

    enrolmentApi.accept(submission.submissionId(), "staff.two");

    var member = policyApi.listMembers(creditLifeScheme, null, "Peter",
        PageRequest.of(0, 10)).getContent().get(0);
    assertThat(member.coveredAmount()).isEqualByComparingTo("25000000.00");
    assertThat(member.underwritingCaseId()).isNotNull();
}
```

- [ ] **Step 2: Run test to verify it fails** — `./mvnw test -Dtest=EnrolmentIntegrationTest`. Expected: FAIL, `accept` does not exist.

- [ ] **Step 3: Implement acceptance**

`accept` is `@Transactional`: the whole file lands or none of it does. Per row with
outcome `ENROLLED` or `ENROLLED_CAPPED`, call
`policyApi.addMember(policyNumber, MemberInput.borrower(name, dob, loanAccountNumber, terms), acceptedBy)`
and write the returned `policyMemberId` back onto the row.

**Loan terms are built from the row plus the scheme, in ONE private method**, because
three of `LoanTerms`' six components are no longer in the file at all:

```java
    /**
     * A row plus its scheme make a loan.
     *
     * <p>THE ONE PLACE these three derivations live, and the one place to change when
     * client question A is answered:
     *
     * <ul>
     *   <li><b>rate</b> is ZERO. Straight-line decline never reads it --
     *       {@code outstandingPrincipalAt} on FLAT_RATE is {@code principal x (n-k)/n}.
     *       If A comes back "reducing balance", the rate has to return to the template,
     *       because a reducing schedule genuinely needs a per-loan rate.</li>
     *   <li><b>frequency</b> comes from the scheme. A lender's product repays on one
     *       cadence.</li>
     *   <li><b>first repayment</b> is disbursement plus one period. A moratorium can no
     *       longer be expressed at all -- see client question 8.</li>
     * </ul>
     */
    private LoanTerms loanTermsFor(EnrolmentRow row, GroupScheme scheme) {
        RepaymentFrequency frequency = scheme.getRepaymentFrequency();
        return new LoanTerms(row.loanPrincipalAmount(), BigDecimal.ZERO, row.loanTermMonths(),
            frequency, row.disbursementDate(),
            row.disbursementDate().plusMonths(frequency.monthsPerPeriod()));
    }
```

Note `LoanTerms`' own invariant: the term must divide into whole periods. A 50-month loan
on a quarterly scheme throws from the record's constructor, which must be caught during
**judging** and recorded as `MALFORMED_VALUE` naming the term and the cadence — not
allowed to surface during acceptance, where it would roll back a whole accepted file.

An `InvalidPolicyStateException` from `addMember` on a row judged acceptable is a **bug,
not a rejection**: the judging and the enrolling disagree. Let it propagate and roll the
transaction back rather than quietly recording the row as rejected — a silent divergence
between the report and the schedule is the worst outcome this feature can produce.

- [ ] **Step 4: Run tests** — the class, then `./mvnw test -Dtest='*Group*,*Enrolment*'`. Expected: PASS.

- [ ] **Step 5: Negative control**

Temporarily make `accept` skip the `ENROLLED_CAPPED` rows. Expected:
`anAboveFclRowIsEnrolledCappedAndCarriesItsReferral` fails and nothing else — capped rows
are cover, not rejections, and treating them as rejections is the most plausible way to
get this wrong.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy/ \
        backend/src/test/java/tz/co/nlolo/lifeplatform/policy/EnrolmentIntegrationTest.java
git commit -m "feat(policy): a second pair of eyes turns a submission into cover"
```

---

### Task 6: The rejection report, and the HTTP surface

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/EnrolmentApi.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/domain/EnrolmentReportRenderer.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/EnrolmentController.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/EnrolmentSubmissionResponseDto.java`
- Modify: `backend/api/openapi/openapi-policy.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/EnrolmentReportRendererTest.java`, and extend `PolicyContractTest`

**Interfaces:**
- Consumes: everything above.
- Produces: `EnrolmentReportRenderer.toCsv(List<EnrolmentRowView>) -> String`; `EnrolmentApi.renderReport(UUID) -> String`; three endpoints.

- [ ] **Step 1: Write the failing test**

```java
@Test
void theReportCarriesOneLinePerRowWithItsOutcome() {
    String csv = EnrolmentReportRenderer.toCsv(List.of(
        new EnrolmentRowView(2, "LN-A", "Amina Hassan Mwinyi", RowOutcome.ENROLLED, null, null, UUID.randomUUID()),
        new EnrolmentRowView(3, "LN-B", "", RowOutcome.REJECTED,
            EnrolmentRejection.MISSING_REQUIRED_FIELD,
            "borrower_full_name is blank. THIS BORROWER IS NOT COVERED.", null)));

    assertThat(csv.lines().findFirst().orElseThrow())
        .isEqualTo("row_number,loan_account_number,borrower_full_name,outcome,reason_code,reason");
    assertThat(csv).contains("2,LN-A,Amina Hassan Mwinyi,ENROLLED,,");
    assertThat(csv).contains("THIS BORROWER IS NOT COVERED.");
}

@Test
void aReasonContainingACommaOrAQuoteIsEscaped() {
    // The reason text quotes amounts and column names back at the lender. An unescaped
    // comma silently shifts every later column, which on a report about who is insured
    // is worse than no report.
    String csv = EnrolmentReportRenderer.toCsv(List.of(
        new EnrolmentRowView(2, "LN-A", "Mwinyi, Amina", RowOutcome.REJECTED,
            EnrolmentRejection.MALFORMED_VALUE,
            "loan_principal_amount \"8.5E+06\" is not an amount. THIS BORROWER IS NOT COVERED.", null)));

    assertThat(csv).contains("\"Mwinyi, Amina\"");
    assertThat(csv).contains("\"\"8.5E+06\"\"");
}
```

- [ ] **Step 2: Run test to verify it fails** — compilation.

- [ ] **Step 3: Write the renderer** using `CSVPrinter` with `CSVFormat.DEFAULT`, which
handles the escaping. Do not hand-roll it: hand-rolled CSV escaping is how a comma in a
name silently shifts a column.

- [ ] **Step 4: Write the controller**

```java
@PostMapping(value = "/credit-life-schemes/{policyNumber}/enrolments",
             consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
@PreAuthorize("hasRole('REALM_STAFF')")
```

Plus `POST .../enrolments/{submissionId}/acceptance`, `POST .../enrolments/{submissionId}/withdrawal`,
`GET .../enrolments/{submissionId}` and `GET .../enrolments/{submissionId}/report`
(`text/csv`, `Content-Disposition: attachment`).

**`REALM_STAFF` only in this plan.** The bank-facing path needs a public PKCE client on
the `customers` realm, real CORS and the corporate-party binding — spec §4 items 18-21,
all of which are on the production-readiness gate and none of which belong in a product
build. Say so in the controller javadoc.

`jwt.getSubject()` is the `submittedBy` and `acceptedBy`. The two-person rule is only as
real as that identity, so it must never come from the request body.

- [ ] **Step 5: Update the OpenAPI spec and regenerate**

Add the five paths to `openapi-policy.yaml`, then `cd frontend && npm run generate:api`.
The contract tests validate responses against this spec, so a missing schema fails them.

- [ ] **Step 6: Run the tests**

Run, with the dev backend stopped: `./mvnw clean test`
Expected: BUILD SUCCESS. Baseline is **1230** — record the new number.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy/ backend/api/openapi/openapi-policy.yaml \
        backend/src/test/java/tz/co/nlolo/lifeplatform/policy/
git commit -m "feat(policy): a lender gets back a report that says, per row, who is covered"
```

---

### Task 7: XLSX at the edge — OPTIONAL, decide before starting

Both real lender files are `.xlsx`. The template is ours and may mandate CSV, so this task
exists to be **deliberately included or deliberately dropped**, not defaulted into.

**If included:** add `org.apache.poi:poi-ooxml`, allow the XLSX media type, and convert a
workbook to CSV text *before* the parser — one function, `XlsxToCsv.convert(InputStream)
-> String`, so that validation, the report and every test above continue to see exactly
one shape.

The conversion carries the whole hazard, and each of these is a test:

- **Amounts are doubles in XLSX.** Read cells as strings via `DataFormatter`, never
  `getNumericCellValue()`, or `8500000.00` round-trips as `8499999.999999999`.
- **Dates are serial numbers.** `46203` is 2026-06-30. Use `DateUtil.isCellDateFormatted`
  and emit ISO; a serial reaching the parser is `MALFORMED_VALUE`, which is safe but
  useless to the lender.
- **Numeric-looking account numbers become scientific notation** and lose leading zeros.
- **Trailing formula residue**: the real LOLC sheet has ~200 rows below the data carrying
  `1`s and `0`s. Skip a row where every *required* column is blank.
- **POI reads the whole workbook into memory.** Fine at 400 rows; keep the multipart cap.

**If dropped:** reject an XLSX upload with a message naming the template and pointing at
`credit-life-enrolment-sample.csv`, and say in the controller javadoc that this was a
decision rather than an omission.

---

## Done when

- A CSV reaches the application: allowlist, dependency and multipart limits all permit it.
- A lender's file is parsed without trusting a cell: BOM, header case, scientific notation, Excel serials and trailing residue all handled, each with a test.
- A submission records every row and its outcome and **enrols nobody**.
- A rejected row's reason ends with `THIS BORROWER IS NOT COVERED.`
- A second, different staff user turns a submission into cover; the uploader cannot.
- One submission in flight per scheme; accepting or withdrawing frees it.
- An above-FCL row enrols capped and carries its underwriting referral.
- The report escapes commas and quotes.
- `./mvnw clean test` green with the dev backend stopped.

## Explicitly NOT in this plan

Premium, invoicing, refunds and commission (Plan 3) · exits and the reconciliation file (Plan 3) · the claim path and payout (Plan 4) · `regreporting` and the emailed report with its acknowledgement (Plan 5) · every screen (Plan 6) · the bank-facing portal and the four production-readiness items it needs (Plan 7).

**Two dependencies outside this plan.** Client question A decides whether the three optional loan columns become required — Task 5 keeps their defaults in one method for that reason. And both lenders must add a loan account number to their export before a single real file will pass the header check.
