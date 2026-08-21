# M11 — `document`/`refdata` APIs and a loginable local environment — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the platform's last two API gaps (including a real defect — claim evidence is currently write-only over HTTP) and make the local stack something a browser can actually log into, so frontend work (M12) can begin.

**Architecture:** No new module and no module-graph change. `claims` gains one download endpoint (it already declares `document::api`); `document` and `refdata` each gain their first controller. A `document/V2` migration adds the two columns that make a download endpoint able to return a real `Content-Type` and filename. The bundled 2-document OpenAPI file is split so both modules can finally have contract tests. Separately, the four Keycloak realms gain the two protocol mappers without which no issued token can pass `TenantContextFilter`, plus test users, and a seeder that drives real HTTP through the underwriting→auto-issue event chain.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith, Postgres 16 (schema-per-module + RLS), MinIO, Keycloak 24, Testcontainers, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-08-20-m11-document-refdata-apis-design.md` (`b966860`, expanded `8f80ca7`, `e7be244`).

---

## Global Constraints

Every task's requirements implicitly include this section.

- **No module-graph change.** `document` must NOT gain a dependency on `claims`, `party`, or `policy`. Those four modules (`claims`, `party`, `policy`, `underwriting`) all declare `document::api`, so any reverse edge is a cycle `NoCircularDependencyTest` fails on. This is why the customer-facing download lives in `claims`, not in a generic document controller.
- **`document`/`refdata` `package-info.java` currently carry a bare `@ApplicationModule` with no `allowedDependencies`** — verified. Do not add one. Neither module gains a dependency in this milestone.
- **Endpoint 1's dual check is the milestone's most important invariant.** `GET /claims/{claimId}/evidence/{documentRef}` must verify BOTH that the caller owns the claim AND that the document's `ownerContext` equals `"claim:" + claimId`. Checking only the claim reproduces M7's nested-resource IDOR (`AgentController` verified `agentId` but never that `statementId` belonged to it), which passed 26/26 contract tests. A test must fail if the second check is removed.
- **Cross-tenant and denied reads must be indistinguishable from not-found.** `DocumentApiImpl.findOrThrow` already reports a cross-tenant ref with the identical exception and message as a missing one (Task 1 keeps that property while changing the exception *type* — see the next constraint). Endpoint 1's document-to-claim mismatch and endpoint 4's allowlist denial must both do the same, or each becomes an oracle for resources the caller may not see.
- **`refdata` is deliberately NOT tenant-scoped and has no RLS** (`db-migrations/refdata/V1__create_refdata_schema.sql:2-5`). Do not add a tenant check to endpoint 4. Do add the per-realm key allowlist.
- **Never edit an already-applied migration.** `document/V1` and `refdata/V1`-`V4` are immutable; `scripts/migrate.sh` applies every `V*.sql` per module in version order. Add `document/V2`.
- **Column widths must be checked against real values in the same migration.** M7 shipped a CHECK admitting a 16-character value into a `VARCHAR(15)`. Measure the longest literal.
- **Money and numeric reference values stay strings on the wire.** `reference_code_set.value` is `VARCHAR(255)` holding everything from `'24'` to `'12.0'` to a comma-separated list; return it verbatim as a string, never a JSON number (`docs/06-database-schema.md:32`).
- **Contract tests pair `openApi().isValid(SPEC_PATH)` with `SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, schemaName)`** on every JSON response. `isValid` does not enforce primitive JSON types (measured on this platform). Exact signature: `public static ResultMatcher matchesDeclaredTypes(String specPath, String schemaName)` (`src/test/java/tz/co/nlolo/lifeplatform/SpecTypeConformance.java:126`).
- **`MigrationTestSupport.applyMigration(String jdbcUrl, String username, String password, String... migrationPaths)`** (`src/test/java/tz/co/nlolo/lifeplatform/MigrationTestSupport.java:22`).
- **Quote every YAML description containing a comma.** An unquoted flow-style description with a comma broke a contract test's spec load in M4.
- **Explicit bean names are required where a simple class name already exists elsewhere.** Run `find src/main/java -name "<NewClass>.java"` before creating any class — Spring Data derives repository bean names and JPA derives entity names from the simple class name. `DocumentController` and `ReferenceDataController` are new platform-wide (verified), so no prefix is needed, but re-verify before creating.
- **A raw `NoSuchElementException` becomes a 500 on this platform, NOT a 404.** Verified: `GlobalExceptionHandler` maps only `Exception`, `IllegalArgumentException`, `AccessDeniedException` and `OptimisticLockingFailureException`. Every module that returns 404 defines its own typed exception in its `api` package and maps it in its own `@RestControllerAdvice` — ten such handlers exist (`ClaimExceptionHandler`, `PartyExceptionHandler`, `BillingExceptionHandler`, …), each following `@ExceptionHandler(XNotFoundException.class) → problem(HttpStatus.NOT_FOUND, ex.getMessage(), "X_NOT_FOUND")`. **`DocumentApiImpl` today throws a bare `NoSuchElementException`, so a missing or cross-tenant document currently produces a 500.** Task 1 fixes that as a prerequisite; Tasks 3–5 depend on it. Do not "fix" this by adding `NoSuchElementException` to `GlobalExceptionHandler` — that is shared config, would change behaviour for every module at once, and a second advice competing for the same type is the shadowing bug `@Order` exists to prevent.
- **Build commands.** Always on the host, in the FOREGROUND, never in Docker (breaks Testcontainers networking), and never backgrounded-then-waited-on (a subagent cannot receive a completion notification for its own background process):
  ```bash
  export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
  ./mvnw -B -o test -Dtest=SomeSpecificTest      # the normal case
  ./mvnw -B -o test                              # only when the rule below says so
  ```
- **Run the NARROWEST test set that could detect a regression.** Run the FULL suite only when the task: (1) changes production code outside `document`/`refdata`/`claims`; (2) changes a shared test class or utility; (3) changes shared config (`pom.xml`, `application.yml`, `SecurityConfig`, `Application.java`); (4) changes a migration an existing test's own migration list applies — **`document/V1` IS applied by existing tests** (`ClaimEvidenceIntegrationTest`, and any test listing `document/V1`), so Task 1 must run the full suite; (5) is the final verification task (Task 10).
- **Baseline before M11: 642 tests, 0 failures, 0 errors** (`main` at `f5f817e`, independently re-verified). Quote the count from whatever you actually ran and label it (`full suite` vs `-Dtest=X`).
- **Never report coverage you did not execute.**

---

## File Structure

**New — production:**
- `db-migrations/document/V2__add_content_type_and_file_name.sql`
- `src/main/java/tz/co/nlolo/lifeplatform/document/infrastructure/DocumentController.java` — the two staff-only endpoints
- `src/main/java/tz/co/nlolo/lifeplatform/document/infrastructure/DocumentMetadataResponseDto.java`
- `src/main/java/tz/co/nlolo/lifeplatform/refdata/infrastructure/ReferenceDataController.java` — endpoint 4 + the allowlist
- `src/main/java/tz/co/nlolo/lifeplatform/refdata/infrastructure/ReferenceCodeSetResponseDto.java`

**Modified — production:**
- `document/api/DocumentApi.java`, `document/api/DocumentMetadataView.java`, `document/domain/DocumentRecord.java`, `document/application/DocumentApiImpl.java` — thread `contentType`/`fileName`
- `claims/infrastructure/ClaimEvidenceController.java` — capture the filename on upload; add endpoint 1

**New — API contracts:**
- `api/openapi/openapi-document.yaml`, `api/openapi/openapi-refdata.yaml` (split out; bundle deleted)

**New — environment:**
- `docs/09-local-development.md`, `scripts/seed-dev-data.sh`

**Modified — environment:**
- `scripts/migrate.sh` (add `local`), `keycloak/{customers,agents,staff,regulators}-realm.json`, `keycloak/README.md`, `infra/docker-compose.yml` (pgAdmin server pre-registration)

**Modified — docs:**
- `docs/04-api-contracts.md` (companion-file list; `finaccounting`/`document`/`refdata` auth rows)

**Tests:**
- `document/DocumentSpecParsesTest`, `refdata/RefdataSpecParsesTest`
- `claims/ClaimEvidenceDownloadTest` (IDOR + round-trip + null-tolerance)
- `document/DocumentContractTest`, `refdata/RefdataContractTest`
- `claims/ClaimEvidenceContractTest` (endpoint 1's HTTP contract)

---

### Task 1: `document/V2`, plus the 404 mapping the download endpoints depend on

**Files:**
- Create: `db-migrations/document/V2__add_content_type_and_file_name.sql`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/document/api/DocumentNotFoundException.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/document/infrastructure/DocumentExceptionHandler.java`
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/document/domain/DocumentRecord.java`
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/document/api/DocumentMetadataView.java`
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/document/api/DocumentApi.java`
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/document/application/DocumentApiImpl.java`
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/claims/infrastructure/ClaimEvidenceController.java`

**Interfaces:**
- Produces, depended on by Tasks 3–6:
  ```java
  // DocumentApi — contentType and fileName appended, in this order
  String upload(String ownerContext, DocumentType documentType, String uploadedBy,
                InputStream content, long contentLength, String contentType, String fileName);
  byte[] download(String documentRef);                     // unchanged
  DocumentMetadataView getMetadata(String documentRef);    // shape changed below

  public record DocumentMetadataView(String documentRef, String ownerContext, DocumentType documentType,
                                     String contentType, String fileName,
                                     String uploadedBy, Instant uploadedAt) {}

  // DocumentRecord — new all-args constructor, contentType/fileName after documentType
  public DocumentRecord(String documentRef, UUID tenantId, String ownerContext, DocumentType documentType,
                        String contentType, String fileName, String uploadedBy, Instant uploadedAt);
  public String getContentType();   // nullable
  public String getFileName();      // nullable

  // NEW — what every "not found" path in Tasks 3-4 throws. Maps to 404 / DOCUMENT_NOT_FOUND.
  public class DocumentNotFoundException extends RuntimeException {
      public DocumentNotFoundException(String message);
  }
  ```

- [ ] **Step 1: Read the current state first**

Read `db-migrations/document/V1__create_document_schema.sql` in full. Note: `document_ref VARCHAR(255) PRIMARY KEY`, `owner_context VARCHAR(50) NOT NULL`, a `document_type` CHECK over five values, RLS enabled with `document_record_tenant_isolation`, and grants to `app_role` including `ALTER DEFAULT PRIVILEGES`. Then read `document/application/DocumentApiImpl.java` and confirm `upload` already receives a `contentType` parameter and passes it only to `storage.put(...)`, never persisting it.

- [ ] **Step 2: Write the migration**

```sql
-- Module: document V2 -- M11 Task 1.
--
-- V1 stored no content type and no original filename. DocumentApiImpl.upload already RECEIVES
-- a contentType and hands it to MinIO's putObject, but nothing on the read path can recover it:
-- MinioDocumentStorage.get returns a bare byte[], and document_record has no column for it.
-- The original filename was never captured at all -- ClaimEvidenceController does not read
-- MultipartFile.getOriginalFilename().
--
-- DocumentType is NOT a substitute: MinioDocumentStorage.bucketFor maps it to a BUCKET
-- (kyc-evidence, claim-evidence, underwriting-evidence, policy-documents), not to a media type.
-- A CLAIM_EVIDENCE document is equally likely to be a JPEG or a PDF.
--
-- Without these two columns, M11's download endpoints could only ever serve
-- application/octet-stream named after a UUID -- so an uploaded certificate photo could never
-- render inline in the customer portal, and a saved file would arrive named 3f9a...-b21c.
--
-- BOTH COLUMNS ARE NULLABLE ON PURPOSE. Documents uploaded before this migration genuinely have
-- neither value and cannot be backfilled -- MinIO holds the content type on the object, but
-- reconciling object metadata back into Postgres for rows we cannot even enumerate per tenant
-- under RLS is not worth it for a column whose only consumer degrades gracefully. The read path
-- MUST tolerate null (M11 Task 3 covers that path with its own test).
--
-- WIDTH CHECK (platform convention: verify against real values, never estimate).
--   content_type: the longest IANA media type in real use is well under 100 chars
--     ('application/vnd.openxmlformats-officedocument.presentationml.presentation' is 73);
--     VARCHAR(255) is ample.
--   file_name: browsers cap upload filenames far below 255 bytes; VARCHAR(255) is ample.
-- Also re-verified while here, on V1's existing column: owner_context VARCHAR(50) still fits its
-- only real producer -- "claim:" + a 36-char UUID is 42 characters. That leaves only 8 characters
-- of headroom, so a future "underwriting-case:{uuid}" producer (18 + 36 = 54) would NOT fit and
-- must widen the column in the same migration that introduces it. Recorded because the design
-- spec's 2.1 anticipates exactly such producers.
-- =============================================================================
ALTER TABLE document.document_record ADD COLUMN content_type VARCHAR(255);
ALTER TABLE document.document_record ADD COLUMN file_name VARCHAR(255);

COMMENT ON COLUMN document.document_record.content_type IS
    'IANA media type as declared by the uploading client. NULL for rows created before document/V2; readers must fall back to application/octet-stream.';
COMMENT ON COLUMN document.document_record.file_name IS
    'Original filename as supplied by the uploading client. NULL for rows created before document/V2; readers must fall back to the document_ref.';
```

- [ ] **Step 3: Add the fields to `DocumentRecord`**

Insert after the existing `documentType` field, keeping the established `@Column` style:

```java
    @Column(name = "content_type")
    private String contentType;

    @Column(name = "file_name")
    private String fileName;
```

Replace the all-args constructor with the signature in **Interfaces** above (assigning all eight fields), keep the `protected DocumentRecord() {}` no-arg for Hibernate, and add:

```java
    public String getContentType() { return contentType; }
    public String getFileName() { return fileName; }
```

- [ ] **Step 4: Widen `DocumentMetadataView` and `DocumentApi.upload`**

`DocumentMetadataView` becomes exactly the record in **Interfaces**. `DocumentApi.upload` gains `String contentType, String fileName` as its last two parameters (`contentType` was already there — append `fileName` after it).

- [ ] **Step 5: Thread both through `DocumentApiImpl`, and fix the 500-instead-of-404**

In `upload`, pass both into the `DocumentRecord` constructor. In `getMetadata`, map both into the view.

Keep `findOrThrow`'s fail-loud tenant check and its "cross-tenant is reported identically to
not-found" behaviour exactly as designed — that is load-bearing. But **change what it throws**:
both its `orElseThrow` and its tenant-mismatch branch currently throw a bare
`NoSuchElementException`, which `GlobalExceptionHandler` does not map, so a missing or cross-tenant
document surfaces as a **500** rather than a 404. Replace both with `DocumentNotFoundException`,
keeping the message byte-identical so the two cases stay indistinguishable:

```java
    private DocumentRecord findOrThrow(String documentRef) {
        DocumentRecord record = repository.findById(documentRef)
            .orElseThrow(() -> new DocumentNotFoundException("No document found for ref " + documentRef));
        // ... existing comment block, unchanged ...
        if (!record.getTenantId().equals(TenantContext.get())) {
            throw new DocumentNotFoundException("No document found for ref " + documentRef);
        }
        return record;
    }
```

Remove the now-unused `java.util.NoSuchElementException` import.

- [ ] **Step 5a: Create the exception and its handler**

`document/api/DocumentNotFoundException.java`, matching `claims/api/ClaimNotFoundException.java`
exactly (read it first — it is a four-line class):

```java
package tz.co.nlolo.lifeplatform.document.api;

/** 404 at the REST boundary. Thrown for a missing ref AND for a cross-tenant ref, with an
 * identical message, so a caller cannot infer that another tenant's document exists. */
public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException(String message) { super(message); }
}
```

`document/infrastructure/DocumentExceptionHandler.java` — read
`claims/infrastructure/ClaimExceptionHandler.java` first and copy its shape verbatim (including
`@Order(Ordered.HIGHEST_PRECEDENCE)` and the private `problem(...)` helper that sets both
`errorCode` and `traceId`, since `openapi-common.yaml` marks `traceId` required):

```java
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DocumentExceptionHandler {

    @ExceptionHandler(DocumentNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(DocumentNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "DOCUMENT_NOT_FOUND");
    }
    // + the same private problem(...) helper ClaimExceptionHandler uses
}
```

Map **only** `DocumentNotFoundException`. Do not map `IllegalArgumentException` or
`AccessDeniedException` — `GlobalExceptionHandler` already owns those, and a duplicate advice is
the shadowing bug `@Order` exists to prevent.

- [ ] **Step 5b: Do the same for `refdata`, which Task 5 needs**

`refdata/api/ReferenceCodeSetNotFoundException.java` and
`refdata/infrastructure/RefdataExceptionHandler.java`, identical in shape, mapping to 404 with
`errorCode` `REFERENCE_CODE_SET_NOT_FOUND`. Task 5's allowlist returns this for **both** a denied
key and an unknown key, which is what keeps them indistinguishable. Doing it here rather than in
Task 5 keeps every exception-plumbing change in one reviewable commit.

- [ ] **Step 6: Update the only caller**

`ClaimEvidenceController.attachEvidence` currently calls `documentApi.upload(...)` with six arguments. Add the filename as the seventh:

```java
            documentRef = documentApi.upload("claim:" + claimId, DocumentType.CLAIM_EVIDENCE, jwt.getSubject(),
                file.getInputStream(), file.getSize(), file.getContentType(), file.getOriginalFilename());
```

- [ ] **Step 7: Run the FULL suite and commit**

Full suite is required by Global Constraints trigger 4: `document/V1` is applied by existing tests' own migration lists, so a `document` schema change can break them.

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -o test
```
Expected: 642 tests, 0 failures, 0 errors. Any test that constructs a `DocumentRecord` or asserts on `DocumentMetadataView`'s shape will need updating — that is expected fallout of the signature change, not a defect.

```bash
git add db-migrations/document/V2__add_content_type_and_file_name.sql src/main/java/tz/co/nlolo/lifeplatform/document src/main/java/tz/co/nlolo/lifeplatform/claims
git commit -m "feat: persist document content type and original filename"
```

---

### Task 2: Split the bundled OpenAPI file

**Files:**
- Create: `api/openapi/openapi-document.yaml`, `api/openapi/openapi-refdata.yaml`
- Delete: `api/openapi/openapi-regreporting-document-refdata.yaml`
- Modify: `docs/04-api-contracts.md`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/document/DocumentSpecParsesTest.java`, `src/test/java/tz/co/nlolo/lifeplatform/refdata/RefdataSpecParsesTest.java`

**Interfaces:**
- Produces: `api/openapi/openapi-document.yaml` and `api/openapi/openapi-refdata.yaml` as the `SPEC_PATH` constants Tasks 4–6's contract tests load.

- [ ] **Step 1: Write both failing spec-parse tests**

Copy `src/test/java/tz/co/nlolo/lifeplatform/regreporting/RegreportingSpecParsesTest.java`'s exact structure (read it first), changing only the path and asserted keys.

```java
package tz.co.nlolo.lifeplatform.document;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Container-free proof that openapi-document.yaml is a single loadable document. Until M11 this
 * module's spec lived inside a "---"-separated multi-document file, which swagger-parser cannot
 * load at all -- which is precisely why neither document nor refdata could ever have a contract
 * test. M4 lost time to an unquoted flow-style description containing a comma breaking a whole
 * contract class's spec load; localising that to one fast test is the point.
 */
class DocumentSpecParsesTest {

    @Test
    void theDocumentSpecParsesAndEveryRefResolves() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        SwaggerParseResult result = new OpenAPIV3Parser()
            .readLocation("api/openapi/openapi-document.yaml", null, options);

        assertThat(result.getMessages())
            .as("openapi-document.yaml must parse with no errors or unresolved $refs")
            .isEmpty();
        assertThat(result.getOpenAPI()).isNotNull();
        assertThat(result.getOpenAPI().getPaths()).containsKeys(
            "/claims/{claimId}/evidence/{documentRef}", "/documents/{documentRef}",
            "/documents/{documentRef}/metadata");
    }
}
```

```java
package tz.co.nlolo.lifeplatform.refdata;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Container-free proof that openapi-refdata.yaml is a single loadable document. See
 * {@code DocumentSpecParsesTest} for why this exists as its own test. */
class RefdataSpecParsesTest {

    @Test
    void theRefdataSpecParsesAndEveryRefResolves() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        SwaggerParseResult result = new OpenAPIV3Parser()
            .readLocation("api/openapi/openapi-refdata.yaml", null, options);

        assertThat(result.getMessages())
            .as("openapi-refdata.yaml must parse with no errors or unresolved $refs")
            .isEmpty();
        assertThat(result.getOpenAPI()).isNotNull();
        assertThat(result.getOpenAPI().getPaths()).containsKey("/reference-codes/{codeSetKey}");
    }
}
```

- [ ] **Step 2: Run both and confirm they fail**

```bash
./mvnw -B -o test -Dtest='DocumentSpecParsesTest,RefdataSpecParsesTest'
```
Expected: FAIL — neither file exists yet, so `getMessages()` is non-empty and `getOpenAPI()` is null.

- [ ] **Step 3: Write `api/openapi/openapi-document.yaml`**

Note `claims`' evidence-download path lives in THIS document, not in `openapi-claims.yaml`: the endpoint is a document capability reached through a claim, and keeping it here means `DocumentContractTest` can validate all three document responses against one spec. `openapi-claims.yaml` is left untouched.

```yaml
openapi: 3.1.0
info:
  title: Document & Content Management API
  version: "1.0.0"
  description: >-
    Document retrieval. Uploads happen through the owning aggregate's own endpoint (today only
    POST /claims/{claimId}/evidence), because a document's authorization is always the owning
    resource's authorization -- this module cannot check ownership itself without depending on
    the modules that depend on it.
servers:
  - url: https://api.nlolo-lifeplatform.tz/v1
paths:
  /claims/{claimId}/evidence/{documentRef}:
    get:
      summary: Download one evidence file attached to a claim
      description: >-
        Requires BOTH that the caller may read the claim AND that the document belongs to that
        claim. A document belonging to a different claim returns 404, never the file, even when
        the caller legitimately owns the claim named in the path.
      security:
        - customersAuth: [profile]
        - agentsAuth: [agent]
        - staffAuth: []
      parameters:
        - name: claimId
          in: path
          required: true
          schema: { type: string, format: uuid }
        - name: documentRef
          in: path
          required: true
          schema: { type: string }
      responses:
        '200':
          description: The file. Content-Type is the media type declared at upload, or application/octet-stream when unknown.
          content:
            application/octet-stream:
              schema: { type: string, format: binary }
        '401': { $ref: 'openapi-common.yaml#/components/responses/Unauthorized' }
        '403': { $ref: 'openapi-common.yaml#/components/responses/Forbidden' }
        '404': { $ref: 'openapi-common.yaml#/components/responses/NotFound' }
  /documents/{documentRef}:
    get:
      summary: Download any document in the caller's tenant (staff only)
      description: >-
        Staff-only, because staff are authorized tenant-wide and so need no object-level check.
        Customers and agents must use the owning aggregate's endpoint instead.
      security:
        - staffAuth: []
      parameters:
        - name: documentRef
          in: path
          required: true
          schema: { type: string }
      responses:
        '200':
          description: The file. Content-Type is the media type declared at upload, or application/octet-stream when unknown.
          content:
            application/octet-stream:
              schema: { type: string, format: binary }
        '401': { $ref: 'openapi-common.yaml#/components/responses/Unauthorized' }
        '403': { $ref: 'openapi-common.yaml#/components/responses/Forbidden' }
        '404': { $ref: 'openapi-common.yaml#/components/responses/NotFound' }
  /documents/{documentRef}/metadata:
    get:
      summary: Read a document's metadata without transferring its content (staff only)
      security:
        - staffAuth: []
      parameters:
        - name: documentRef
          in: path
          required: true
          schema: { type: string }
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { $ref: '#/components/schemas/DocumentMetadataView' }
        '401': { $ref: 'openapi-common.yaml#/components/responses/Unauthorized' }
        '403': { $ref: 'openapi-common.yaml#/components/responses/Forbidden' }
        '404': { $ref: 'openapi-common.yaml#/components/responses/NotFound' }
components:
  securitySchemes:
    customersAuth:
      type: openIdConnect
      openIdConnectUrl: https://iam.nlolo-lifeplatform.tz/realms/customers/.well-known/openid-configuration
    agentsAuth:
      type: openIdConnect
      openIdConnectUrl: https://iam.nlolo-lifeplatform.tz/realms/agents/.well-known/openid-configuration
    staffAuth:
      type: openIdConnect
      openIdConnectUrl: https://iam.nlolo-lifeplatform.tz/realms/staff/.well-known/openid-configuration
  schemas:
    DocumentMetadataView:
      type: object
      required: [documentRef, ownerContext, documentType, uploadedAt]
      properties:
        documentRef: { type: string }
        ownerContext:
          type: string
          description: >-
            Opaque owning-aggregate reference, currently always "claim:{uuid}". Treated as opaque
            on purpose -- new producers will introduce new shapes, so do not parse it.
        documentType: { type: string, enum: [KYC_EVIDENCE, POLICY_DOCUMENT, CLAIM_EVIDENCE, SIGNED_FORM, UNDERWRITING_EVIDENCE] }
        contentType: { type: string, nullable: true, description: "Null for documents uploaded before document/V2." }
        fileName: { type: string, nullable: true, description: "Null for documents uploaded before document/V2." }
        uploadedBy: { type: string, nullable: true }
        uploadedAt: { type: string, format: date-time }
```

- [ ] **Step 4: Write `api/openapi/openapi-refdata.yaml`**

```yaml
openapi: 3.1.0
info:
  title: Reference & Master Data API
  version: "1.0.0"
  description: >-
    Global, non-tenant-scoped lookup and parameter data. PLACEHOLDER VALUES: every seeded value is
    marked placeholder in refdata/V1's own table comment and must not be treated as confirmed
    without Legal, Compliance, Product and Actuarial sign-off -- a client rendering "12.0" as a
    loan interest rate is rendering an unconfirmed number. Access is allowlisted per realm, so a
    key readable by staff may return 404 for a customer.
servers:
  - url: https://api.nlolo-lifeplatform.tz/v1
paths:
  /reference-codes/{codeSetKey}:
    get:
      summary: Read one reference code set
      description: >-
        A key the caller's realm may not read returns 404, identical to a key that does not exist,
        so this endpoint cannot be used to discover which keys exist.
      security:
        - customersAuth: []
        - agentsAuth: []
        - staffAuth: []
        - regulatorsAuth: []
      parameters:
        - name: codeSetKey
          in: path
          required: true
          schema: { type: string }
          example: TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ReferenceCodeSetView' }
        '401': { $ref: 'openapi-common.yaml#/components/responses/Unauthorized' }
        '404': { $ref: 'openapi-common.yaml#/components/responses/NotFound' }
components:
  securitySchemes:
    customersAuth:
      type: openIdConnect
      openIdConnectUrl: https://iam.nlolo-lifeplatform.tz/realms/customers/.well-known/openid-configuration
    agentsAuth:
      type: openIdConnect
      openIdConnectUrl: https://iam.nlolo-lifeplatform.tz/realms/agents/.well-known/openid-configuration
    staffAuth:
      type: openIdConnect
      openIdConnectUrl: https://iam.nlolo-lifeplatform.tz/realms/staff/.well-known/openid-configuration
    regulatorsAuth:
      type: openIdConnect
      openIdConnectUrl: https://iam.nlolo-lifeplatform.tz/realms/regulators/.well-known/openid-configuration
  schemas:
    ReferenceCodeSetView:
      type: object
      required: [codeSetKey, values]
      properties:
        codeSetKey: { type: string }
        values:
          type: array
          items: { $ref: '#/components/schemas/ReferenceCodeView' }
    ReferenceCodeView:
      type: object
      required: [code, label, value]
      properties:
        code: { type: string }
        label: { type: string }
        value:
          type: string
          description: >-
            Always a STRING, never a JSON number, even when it holds "24" or "12.0". The column is
            VARCHAR(255) and its semantics vary per key -- some values are comma-separated lists.
        jurisdiction: { type: string, nullable: true, example: TZ }
```

- [ ] **Step 5: Delete the bundle and fix the one live reference**

```bash
git rm api/openapi/openapi-regreporting-document-refdata.yaml
```

In `docs/04-api-contracts.md` line 4, replace `openapi-regreporting-document-refdata.yaml` (three documents in one file, split before tooling use — noted in-file) with `openapi-regreporting.yaml`, `openapi-document.yaml`, `openapi-refdata.yaml`.

The five historical references in `docs/superpowers/plans/2026-08-20-m10-regreporting.md` (lines 65, 1220, 1225) and `docs/superpowers/specs/2026-08-20-m10-regreporting-design.md` (lines 46, 198) are **left exactly as written** — they accurately describe the file as it stood during M10, and rewriting a completed milestone's record to match a later state destroys the history of why those decisions were made. Add one line at the end of M10's spec §198 paragraph instead:

```markdown
> **M11 update:** the remaining two documents were split into `openapi-document.yaml` and
> `openapi-refdata.yaml` and this bundled file was deleted. The references above describe the
> file as it existed during M10 and are kept for that record.
```

- [ ] **Step 6: Run both tests and commit**

```bash
./mvnw -B -o test -Dtest='DocumentSpecParsesTest,RefdataSpecParsesTest'
```
Expected: PASS, 2 tests. The `containsKeys` assertions will fail if a path key is misspelled, which is the point.

```bash
git add api/openapi docs/04-api-contracts.md docs/superpowers/specs/2026-08-20-m10-regreporting-design.md src/test/java/tz/co/nlolo/lifeplatform/document src/test/java/tz/co/nlolo/lifeplatform/refdata
git commit -m "feat: split the bundled OpenAPI file so document and refdata can have contract tests"
```

---

### Task 3: Endpoint 1 — claim-evidence download, with the dual ownership check

**Files:**
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/claims/infrastructure/ClaimEvidenceController.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/claims/ClaimEvidenceDownloadTest.java`

**Interfaces:**
- Consumes: `DocumentApi.getMetadata`/`download`, `DocumentMetadataView`'s new shape, and `DocumentNotFoundException` — all from Task 1, which must land first or this endpoint returns 500 instead of 404. Also `ClaimController.enforceCustomerOwnClaimOnly(ClaimView claim, Jwt jwt, Authentication authentication)` (existing, package-private static at `ClaimController.java:168`).
- Produces: `GET /claims/{claimId}/evidence/{documentRef}`.

- [ ] **Step 1: Write the failing test class**

This is the milestone's most important test. Read `src/test/java/tz/co/nlolo/lifeplatform/claims/ClaimEvidenceIntegrationTest.java` first for the exact Testcontainers + MinIO harness, and copy its container setup and migration list, adding `document/V2`.

```java
package tz.co.nlolo.lifeplatform.claims;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3's three non-negotiable behaviours for GET /claims/{claimId}/evidence/{documentRef}.
 *
 * <p><b>Why the cross-claim test matters more than the others.</b> Endpoint 1 takes TWO
 * caller-supplied identifiers. Verifying only the claim authorizes one of them while the OTHER
 * still selects the resource -- so a customer who legitimately owns claim A could pass their own
 * claimId with a documentRef belonging to a stranger's claim B and receive the file. That is
 * exactly the nested-resource IDOR M7 shipped in AgentController (it verified agentId and never
 * that statementId belonged to it), which the M7 final review caught only after 26/26 contract
 * tests were already green -- the defect lived entirely in the untested COMBINATION of two
 * individually-correct checks. Every other authorization test in this class passes with the
 * ownerContext check deleted; this one does not.
 */
class ClaimEvidenceDownloadTest {

    @Test
    void aDocumentFromAnotherClaimIsNotFoundEvenThoughTheCallerOwnsTheClaimInThePath() {
        // Two claims, two different claimant parties, SAME tenant (so this is object-level
        // authorization being tested, not tenant isolation -- which findOrThrow already covers).
        // Upload evidence to each, then request claim A's URL with claim B's documentRef.
        // MUST be 404 and MUST NOT return bytes.
    }

    @Test
    void downloadReturnsTheExactBytesContentTypeAndFilenameThatWereUploaded() {
        // Round-trip through real MinIO. Asserting 200-with-non-empty-body is NOT sufficient:
        // assert byte equality, Content-Type == the uploaded media type (image/jpeg, NOT
        // application/octet-stream), and Content-Disposition carrying the original filename.
        // Task 1 exists precisely because both headers were previously unrecoverable.
    }

    @Test
    void aPreV2RowWithNullContentTypeAndFileNameStillDownloads() {
        // Insert a document_record row with content_type/file_name NULL, simulating a document
        // uploaded before document/V2, and assert the download still succeeds with
        // application/octet-stream and the documentRef as filename -- no NPE, no malformed header.
        // This is the path EVERY document already in a real deployment will take.
    }
}
```

Fill each body against the harness you read in `ClaimEvidenceIntegrationTest`. The test names and the assertions named in the comments are the contract; the plumbing follows that file's established pattern.

- [ ] **Step 2: Run it and confirm it fails**

```bash
./mvnw -B -o test -Dtest=ClaimEvidenceDownloadTest
```
Expected: FAIL — the endpoint does not exist, so every request 404s at routing (not at the ownership check). Confirm the failure reason is a missing route, not an assertion, so you know the test is exercising what you think.

- [ ] **Step 3: Add the endpoint**

Append to `ClaimEvidenceController`:

```java
    /**
     * Two independent checks, and the second is not redundant. The first authorizes the CLAIM;
     * without the second, a different caller-supplied identifier -- documentRef -- still selects
     * the resource, so owning claim A would be enough to fetch claim B's evidence. That is M7's
     * nested-resource IDOR exactly. A mismatch is reported as 404 rather than 403, identical to a
     * nonexistent ref, so a caller cannot learn that someone else's document exists.
     */
    @GetMapping("/claims/{claimId}/evidence/{documentRef}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<byte[]> downloadEvidence(@PathVariable UUID claimId,
            @PathVariable String documentRef,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        ClaimView claim = claimsApi.getClaim(claimId);
        ClaimController.enforceCustomerOwnClaimOnly(claim, jwt, authentication);

        DocumentMetadataView metadata = documentApi.getMetadata(documentRef);
        if (!("claim:" + claimId).equals(metadata.ownerContext())) {
            // Same exception and same message as a nonexistent ref (Task 1), so "not yours" and
            // "does not exist" are indistinguishable to the caller.
            throw new DocumentNotFoundException("No document found for ref " + documentRef);
        }

        return DocumentResponses.fileResponse(documentApi.download(documentRef), metadata);
    }
```

`claims` may throw `DocumentNotFoundException`: it is in `document.api`, which `claims` already
declares in its `allowedDependencies`.

- [ ] **Step 4: Add the shared response builder**

Both this endpoint and Task 4's staff download need identical header logic, so it lives in one place rather than being duplicated. Create `src/main/java/tz/co/nlolo/lifeplatform/document/infrastructure/DocumentResponses.java`:

```java
package tz.co.nlolo.lifeplatform.document.infrastructure;

import tz.co.nlolo.lifeplatform.document.api.DocumentMetadataView;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The Content-Type/Content-Disposition fallbacks shared by the customer-facing claim-evidence
 * download (claims module) and the staff-only generic download (this module). Both columns are
 * nullable for documents predating document/V2, so both fallbacks are reachable in any real
 * deployment -- not defensive padding.
 */
public final class DocumentResponses {

    private DocumentResponses() {}

    public static ResponseEntity<byte[]> fileResponse(byte[] content, DocumentMetadataView metadata) {
        MediaType mediaType = metadata.contentType() == null
            ? MediaType.APPLICATION_OCTET_STREAM
            : MediaType.parseMediaType(metadata.contentType());
        String filename = metadata.fileName() == null ? metadata.documentRef() : metadata.fileName();

        return ResponseEntity.ok()
            .contentType(mediaType)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString())
            .body(content);
    }
}
```

`claims` may call this: it already declares `document::api`, and `DocumentResponses` is in `document.infrastructure`. **Verify with `ModularityTests` in Step 5** — if Spring Modulith rejects the cross-module `infrastructure` reference, move `DocumentResponses` into `claims/infrastructure` and have Task 4's controller keep its own copy, accepting the duplication rather than weakening the module boundary. Do not add an `allowedDependencies` entry to work around it.

- [ ] **Step 5: Run the test plus the module guard**

```bash
./mvnw -B -o test -Dtest='ClaimEvidenceDownloadTest,ClaimEvidenceIntegrationTest,ModularityTests,NoCircularDependencyTest'
```
Expected: PASS. `ModularityTests` is included deliberately — Step 4's placement decision depends on its verdict.

- [ ] **Step 6: Prove the IDOR test actually detects the bug**

Temporarily delete the `ownerContext` check from Step 3, re-run only the cross-claim test, and confirm it FAILS. Restore the check and confirm it passes again. Record both outcomes in the task report. A test that cannot fail is not coverage — and this exact check is the one M7 shipped without.

```bash
./mvnw -B -o test -Dtest=ClaimEvidenceDownloadTest
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform src/test/java/tz/co/nlolo/lifeplatform/claims
git commit -m "feat: claim evidence is downloadable, gated by claim ownership AND document-to-claim match"
```

---

### Task 4: Endpoints 2–3 — staff-only generic document access

**Files:**
- Create: `src/main/java/tz/co/nlolo/lifeplatform/document/infrastructure/DocumentController.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/document/infrastructure/DocumentMetadataResponseDto.java`

**Interfaces:**
- Consumes: `DocumentApi` (Task 1), `DocumentResponses.fileResponse` (Task 3).
- Produces: `GET /documents/{documentRef}`, `GET /documents/{documentRef}/metadata`.

- [ ] **Step 1: Check for collisions, then confirm the URL is unclaimed**

```bash
find src/main/java -name "DocumentController.java" -o -name "DocumentMetadataResponseDto.java"
grep -rn '"/documents' --include=*.java src/main/java
```
Expected: no output from either. Report the actual output.

- [ ] **Step 2: Write the DTO**

```java
package tz.co.nlolo.lifeplatform.document.infrastructure;

import tz.co.nlolo.lifeplatform.document.api.DocumentMetadataView;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;

import java.time.Instant;

/** {@code contentType} and {@code fileName} are nullable for documents predating document/V2. */
public record DocumentMetadataResponseDto(String documentRef, String ownerContext, DocumentType documentType,
                                           String contentType, String fileName,
                                           String uploadedBy, Instant uploadedAt) {

    public static DocumentMetadataResponseDto from(DocumentMetadataView view) {
        return new DocumentMetadataResponseDto(view.documentRef(), view.ownerContext(), view.documentType(),
            view.contentType(), view.fileName(), view.uploadedBy(), view.uploadedAt());
    }
}
```

- [ ] **Step 3: Write the controller**

```java
package tz.co.nlolo.lifeplatform.document.infrastructure;

import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentMetadataView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generic document access, deliberately STAFF-ONLY.
 *
 * <p>Customers and agents cannot use these endpoints, and that is a design constraint rather than
 * an oversight. Authorizing a document means answering "does this belong to you?", which only the
 * owning aggregate can answer -- and this module cannot ask it: claims, party, policy and
 * underwriting all declare {@code document::api}, so a dependency from here onto any of them is a
 * cycle {@code NoCircularDependencyTest} fails on. Customer-facing access therefore lives in the
 * owning module ({@code ClaimEvidenceController} today). Staff need no such check: they are
 * authorized tenant-wide, and cross-tenant refs are already indistinguishable from not-found
 * inside {@code DocumentApiImpl.findOrThrow}.
 *
 * <p>There is no upload endpoint here for the same reason -- an upload must be attributed to an
 * owning aggregate, which is the aggregate's own business.
 */
@RestController
public class DocumentController {

    private final DocumentApi documentApi;

    public DocumentController(DocumentApi documentApi) {
        this.documentApi = documentApi;
    }

    @GetMapping("/documents/{documentRef}")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<byte[]> download(@PathVariable String documentRef) {
        DocumentMetadataView metadata = documentApi.getMetadata(documentRef);
        return DocumentResponses.fileResponse(documentApi.download(documentRef), metadata);
    }

    @GetMapping("/documents/{documentRef}/metadata")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<DocumentMetadataResponseDto> metadata(@PathVariable String documentRef) {
        return ResponseEntity.ok(DocumentMetadataResponseDto.from(documentApi.getMetadata(documentRef)));
    }
}
```

Note the gate is `hasRole('REALM_STAFF')` with **no** fine-grained role conjunct: any staff member who can act on a claim or a policy needs to read its attachments, and narrowing to `FINANCE_OFFICER` would lock out the claims assessors this milestone exists to unblock.

- [ ] **Step 4: Confirm the 404 path end to end**

Task 1 replaced `findOrThrow`'s bare `NoSuchElementException` with `DocumentNotFoundException` and
added `DocumentExceptionHandler` to map it to 404 — without that, both endpoints here would return
**500** for a missing or cross-tenant ref, because `GlobalExceptionHandler` does not map
`NoSuchElementException`. Verify by request rather than by reading: hit
`GET /documents/does-not-exist` with a staff token and confirm the response is a 404 whose body
carries `errorCode: DOCUMENT_NOT_FOUND` and a `traceId`. Quote the actual response in the task
report. If it is a 500, Task 1's handler is not being picked up — diagnose that rather than
adding a second advice here.

- [ ] **Step 5: Compile and commit**

```bash
./mvnw -B -o -q compile && ./mvnw -B -o test -Dtest='ModularityTests,NoCircularDependencyTest'
git add src/main/java/tz/co/nlolo/lifeplatform/document
git commit -m "feat: staff-only generic document download and metadata endpoints"
```

HTTP-level coverage for these two endpoints is Task 6's contract test, deliberately — a controller with no logic beyond delegation is better covered by a real request than by a unit test with a mocked `DocumentApi`.

---

### Task 5: Endpoint 4 — `refdata` read with a fail-closed per-realm allowlist

**Files:**
- Create: `src/main/java/tz/co/nlolo/lifeplatform/refdata/infrastructure/ReferenceDataController.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/refdata/infrastructure/ReferenceCodeSetResponseDto.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/refdata/ReferenceDataAllowlistTest.java`

**Interfaces:**
- Consumes: `ReferenceDataApi.getCodes(String codeSetKey)` returning `List<ReferenceCodeView>`, where `ReferenceCodeView` is `(String code, String label, String value, String jurisdiction)` — verified against the real record. Also `ReferenceCodeSetNotFoundException` and `RefdataExceptionHandler`, both created in Task 1 Step 5b; without them a denied or unknown key returns 500 and the allowlist tests fail for the wrong reason.
- Produces: `GET /reference-codes/{codeSetKey}`.

- [ ] **Step 1: Write the failing allowlist test**

The security-critical assertion is a customer being denied the pricing basis. Use `@WebMvcTest`-style slicing only if the project already does so elsewhere; otherwise follow the contract-test harness and treat this as part of Task 6. **Read the codebase first and pick whichever the project already establishes**, then write:

```java
package tz.co.nlolo.lifeplatform.refdata;

import org.junit.jupiter.api.Test;

/**
 * The allowlist's job is to stop a customer token reading TZ_BASE_PREMIUM_RATE_PER_MILLE -- the
 * company's premium pricing basis. The draft OpenAPI document this milestone replaced carried a
 * blanket customers/agents/staff security block, which would have served exactly that to anyone.
 */
class ReferenceDataAllowlistTest {

    @Test
    void aCustomerCanReadTheLoanInterestRate() {
        // 200, values non-empty, value returned as a JSON STRING "12.0" (never a number).
    }

    @Test
    void aCustomerCannotReadThePremiumPricingBasis() {
        // TZ_BASE_PREMIUM_RATE_PER_MILLE -> 404. The single most important assertion here.
    }

    @Test
    void anAgentCanReadTheFieldReceiptSlaThatACustomerCannot() {
        // OFFLINE_RECEIPT_SLA_HOURS: 200 for agents, 404 for customers. Proves the allowlist is
        // genuinely per-realm rather than one shared list -- a single shared list passes every
        // other test in this class.
    }

    @Test
    void staffCanReadEverySeededKey() {
        // All nine keys -> 200.
    }

    @Test
    void aRegulatorGetsTheDisclosedKeysButNotTheCommercialOnes() {
        // TZ_CONTESTABILITY_MONTHS -> 200; TZ_BASE_PREMIUM_RATE_PER_MILLE -> 404.
    }

    @Test
    void aDeniedKeyAndAnUnknownKeyAreByteIdentical() {
        // Compare the two full response bodies directly, not merely "both are 404" -- otherwise
        // the allowlist becomes an oracle for which keys exist.
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./mvnw -B -o test -Dtest=ReferenceDataAllowlistTest
```
Expected: FAIL — no route exists.

- [ ] **Step 3: Write the DTO**

```java
package tz.co.nlolo.lifeplatform.refdata.infrastructure;

import tz.co.nlolo.lifeplatform.refdata.api.ReferenceCodeView;

import java.util.List;

public record ReferenceCodeSetResponseDto(String codeSetKey, List<ReferenceCodeEntryDto> values) {

    /** {@code value} stays a String even when it holds "24" or "12.0" -- the column is
     * VARCHAR(255) and its meaning varies per key, so coercing to a number would be wrong for
     * POLICY_SUSPENSION_ELIGIBLE_CATEGORIES and lossy for the rest. */
    public record ReferenceCodeEntryDto(String code, String label, String value, String jurisdiction) {
        static ReferenceCodeEntryDto from(ReferenceCodeView view) {
            return new ReferenceCodeEntryDto(view.code(), view.label(), view.value(), view.jurisdiction());
        }
    }

    public static ReferenceCodeSetResponseDto of(String codeSetKey, List<ReferenceCodeView> views) {
        return new ReferenceCodeSetResponseDto(codeSetKey,
            views.stream().map(ReferenceCodeEntryDto::from).toList());
    }
}
```

- [ ] **Step 4: Write the controller with the allowlist**

```java
package tz.co.nlolo.lifeplatform.refdata.infrastructure;

import tz.co.nlolo.lifeplatform.refdata.api.ReferenceCodeSetNotFoundException;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceCodeView;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads one reference code set.
 *
 * <p>{@code refdata.reference_code_set} is deliberately NOT tenant-scoped and carries no RLS
 * policy -- refdata/V1's own header says so ("there is no tenant to isolate") -- so there is no
 * tenant check here, by design.
 *
 * <p><b>But "global" is not "readable by every realm."</b> Two of the nine seeded keys are
 * commercially sensitive: TZ_BASE_PREMIUM_RATE_PER_MILLE is the premium pricing basis, and
 * TZ_COMMISSION_CLAWBACK_MONTHS is agent commercial terms. The Phase 0 draft spec carried a
 * blanket customers/agents/staff security block, which would have served the pricing basis to any
 * customer token. Hence an explicit per-realm allowlist, DEFAULTING TO DENY: a newly seeded key is
 * invisible to every non-staff realm until someone deliberately adds it, which is the right
 * default for a table that grows by migration and whose sensitivity varies per row.
 *
 * <p>A denied key returns 404 identical to a nonexistent key, so the allowlist cannot be used to
 * enumerate which keys exist -- the same principle DocumentApiImpl.findOrThrow applies to
 * cross-tenant document refs.
 */
@RestController
public class ReferenceDataController {

    /** Disclosed in policy terms or directly charged to the customer -- safe for every realm. */
    private static final Set<String> PUBLICLY_DISCLOSED = Set.of(
        "TZ_CONTESTABILITY_MONTHS",
        "TZ_REINSTATEMENT_WINDOW_MONTHS",
        "TZ_SUSPENSION_TO_LAPSE_MONTHS",
        "TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE",
        "DUNNING_ESCALATION_DAYS");

    /** Operational parameters an agent needs but a policyholder has no business reading. */
    private static final Set<String> AGENT_OPERATIONAL = Set.of(
        "OFFLINE_RECEIPT_SLA_HOURS",
        "POLICY_SUSPENSION_ELIGIBLE_CATEGORIES",
        "TZ_COMMISSION_CLAWBACK_MONTHS");

    private static final Map<String, Set<String>> READABLE_BY_REALM = Map.of(
        "ROLE_REALM_CUSTOMERS", PUBLICLY_DISCLOSED,
        "ROLE_REALM_REGULATORS", PUBLICLY_DISCLOSED,
        "ROLE_REALM_AGENTS", union(PUBLICLY_DISCLOSED, AGENT_OPERATIONAL),
        "ROLE_REALM_STAFF", Set.of());   // sentinel: staff read everything, see mayRead

    private final ReferenceDataApi referenceDataApi;

    public ReferenceDataController(ReferenceDataApi referenceDataApi) {
        this.referenceDataApi = referenceDataApi;
    }

    @GetMapping("/reference-codes/{codeSetKey}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF') or hasRole('REALM_REGULATORS')")
    public ResponseEntity<ReferenceCodeSetResponseDto> getCodeSet(@PathVariable String codeSetKey,
            Authentication authentication) {
        if (!mayRead(codeSetKey, authentication)) {
            // Identical exception AND identical message to the not-found path below, deliberately:
            // if a denied key were distinguishable from an unknown one, this endpoint would become
            // an oracle for which keys exist.
            throw new ReferenceCodeSetNotFoundException("No reference code set found for key " + codeSetKey);
        }
        List<ReferenceCodeView> values = referenceDataApi.getCodes(codeSetKey);
        if (values.isEmpty()) {
            throw new ReferenceCodeSetNotFoundException("No reference code set found for key " + codeSetKey);
        }
        return ResponseEntity.ok(ReferenceCodeSetResponseDto.of(codeSetKey, values));
    }

    private static boolean mayRead(String codeSetKey, Authentication authentication) {
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            String authority = granted.getAuthority();
            if ("ROLE_REALM_STAFF".equals(authority)) {
                return true;
            }
            Set<String> readable = READABLE_BY_REALM.get(authority);
            if (readable != null && readable.contains(codeSetKey)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
```

**Note on the empty-result 404:** an unknown key and a key with no rows are the same state to `ReferenceDataApi.getCodes`, and both must look identical to a denied key, so all three return the same 404. This is a deliberate change from the Phase 0 draft's empty-200.

- [ ] **Step 5: Run and commit**

```bash
./mvnw -B -o test -Dtest=ReferenceDataAllowlistTest
```
Expected: PASS, 6 tests.

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/refdata src/test/java/tz/co/nlolo/lifeplatform/refdata
git commit -m "feat: refdata code-set endpoint with a fail-closed per-realm key allowlist"
```

---

### Task 6: Contract tests and doc reconciliation

**Files:**
- Test: `src/test/java/tz/co/nlolo/lifeplatform/document/DocumentContractTest.java`, `src/test/java/tz/co/nlolo/lifeplatform/refdata/RefdataContractTest.java`
- Modify: `docs/04-api-contracts.md`

- [ ] **Step 1: Write both contract tests**

Follow `src/test/java/tz/co/nlolo/lifeplatform/finaccounting/FinaccountingContractTest.java` exactly (read it first): `@Testcontainers` + `@AutoConfigureMockMvc` + `@SpringBootTest(classes = Application.class, webEnvironment = MOCK)`, `MigrationTestSupport.applyMigration(...)`, and `openApi().isValid(SPEC_PATH)` **paired with** `SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, schemaName)` on every JSON response.

Copy these token helpers verbatim from `FinaccountingContractTest:120-138`:

```java
    private static RequestPostProcessor financeStaffOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                                 new SimpleGrantedAuthority("ROLE_FINANCE_OFFICER"))
            .jwt(builder -> builder.subject("finance-officer").claim("tenant_id", tenantId.toString()));
    }

    private static RequestPostProcessor agentOf(UUID tenantId, UUID partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
            .jwt(builder -> builder.subject("agent").claim("tenant_id", tenantId.toString())
                .claim("party_id", partyId.toString()));
    }
```

and add these two, which do not exist anywhere on the platform yet:

```java
    private static RequestPostProcessor customerOf(UUID tenantId, UUID partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(builder -> builder.subject("customer").claim("tenant_id", tenantId.toString())
                .claim("party_id", partyId.toString()));
    }

    /** Regulators carry no fine-grained roles -- SecurityConfig synthesises only
     * ROLE_REALM_<REALM> for them. tenant_id is still mandatory: TenantContextFilter 403s any
     * token without one, which is exactly what makes a regulator tenant-scoped. */
    private static RequestPostProcessor regulatorOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_REGULATORS"))
            .jwt(builder -> builder.subject("tira-regulator").claim("tenant_id", tenantId.toString()));
    }
```

`DocumentContractTest` — migration list `audit/V1`, `refdata/V1`, `party/V1`, `product/V1`, `underwriting/V1`, `policy/V1`-`V4`, `claims/V1`-`V3`, `document/V1`-`V2` (claims fixtures need the claim chain; read `ClaimEvidenceIntegrationTest`'s list and extend it). Assert:
- `GET /documents/{ref}` → 200 for `financeStaffOf`, 403 for `customerOf`, 403 for `agentOf`, 404 for an unknown ref.
- `GET /documents/{ref}/metadata` → 200 for `financeStaffOf` with `matchesDeclaredTypes(SPEC_PATH, "DocumentMetadataView")`, 403 for `customerOf`.
- `GET /claims/{claimId}/evidence/{ref}` → 200 for the owning `customerOf` and for `financeStaffOf`.

`RefdataContractTest` — migration list `refdata/V1`-`V4` only (nothing else is touched). Assert the six allowlist behaviours from Task 5 at the HTTP level, plus `matchesDeclaredTypes(SPEC_PATH, "ReferenceCodeSetView")` on a 200, and explicitly that `values[0].value` serialises as a JSON string (`jsonPath("$.values[0].value").isString()`) — the type-conformance matcher is paired coverage, not a substitute, and this is a decimal-looking value that must not become a number.

- [ ] **Step 2: Run both**

```bash
./mvnw -B -o test -Dtest='DocumentContractTest,RefdataContractTest'
```
Expected: PASS.

- [ ] **Step 3: Reconcile `docs/04-api-contracts.md`**

Three corrections, all verified during design:
1. Line 4's companion-file list — already fixed in Task 2; confirm it stands.
2. §1's module table still lists `finaccounting` as having **no REST surface**. M9 gave it three endpoints (`GET /chart-of-accounts`, `GET /gl-postings`, `GET /gl-postings/{journalEntryId}`). Correct it in the same strikethrough-plus-note style the `reinsurance` row already uses.
3. Lines 60-61's auth-summary rows for `document` and `refdata` describe endpoints that did not exist until this milestone. Replace the aspirational text with what actually ships: `document` — customers/agents read their own claim evidence via the claim, staff read anything in-tenant, no upload endpoint; `refdata` — read, allowlisted per realm, no author/write endpoint.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/tz/co/nlolo/lifeplatform docs/04-api-contracts.md
git commit -m "test: contract coverage for the document and refdata endpoints, and reconcile the API-contracts doc"
```

---

### Task 7: `scripts/migrate.sh local` and the local-development runbook

**Files:**
- Modify: `scripts/migrate.sh`
- Create: `docs/09-local-development.md`
- Modify: `infra/docker-compose.yml` (pgAdmin server pre-registration), create `infra/pgadmin/servers.json`

**Interfaces:**
- Produces: `scripts/migrate.sh local`, relied on by Task 9's seeder and Task 10's verification.

- [ ] **Step 1: Read the script and confirm all three problems**

Read `scripts/migrate.sh` in full. Confirm: it calls bare `psql` (line 39); its `case` accepts only `staging|production`; its header admits "not idempotent — safe for a first migration run only". Note the module order string on line 28 — it is the platform's canonical order and must not be reordered.

- [ ] **Step 2: Add `local` mode**

Extend the `case` block:

```bash
  local)
    # Runs psql INSIDE the postgres container, so no host psql install is needed -- the container
    # already ships it. Each migration is piped in on stdin rather than mounted or `docker cp`ed:
    # docker cp mangles Windows paths on the development machines this targets.
    DB_URL=""            # unused in local mode; every psql call goes through docker compose exec
    LOCAL_MODE=1
    ;;
```

and make the apply loop branch:

```bash
LOCAL_MODE="${LOCAL_MODE:-0}"

apply() {
  local migration="$1"
  if [ "$LOCAL_MODE" = "1" ]; then
    docker compose -f infra/docker-compose.yml exec -T postgres \
      psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 -q < "$migration"
  else
    psql "$DB_URL" -v ON_ERROR_STOP=1 -f "$migration"
  fi
}
```

Replace the loop body's `psql` call with `apply "$migration"`, and correct the final message, which currently counts modules rather than migrations:

```bash
echo "Applied $applied migrations across $(echo $MODULES | wc -w) modules against $ENVIRONMENT."
```

Keep `set -euo pipefail` so a failed migration aborts rather than continuing into a half-migrated schema.

- [ ] **Step 3: Verify it against a real, empty database**

```bash
cd infra && docker compose up -d postgres && cd ..
scripts/migrate.sh local
```
Expected: `Applied 39 migrations across 16 modules against local.` — **39, not 38**: the count was 38 before this milestone and Task 1 added `document/V2`. Then confirm the schema really landed:

```bash
docker compose -f infra/docker-compose.yml exec -T postgres psql -U postgres -d lifeplatform \
  -c "SELECT schemaname, count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema','public','cron','partman') GROUP BY schemaname ORDER BY schemaname;"
```
Expected: 16 schemas. `document` must show 1 table with the two new columns:

```bash
docker compose -f infra/docker-compose.yml exec -T postgres psql -U postgres -d lifeplatform \
  -c "\d document.document_record"
```

- [ ] **Step 4: Pre-register the pgAdmin server**

pgAdmin currently requires hand-registering the server on every fresh volume, and the host must be `postgres` (the compose service name) rather than `localhost` — inside the container `localhost` is pgAdmin itself. Encode it instead. Create `infra/pgadmin/servers.json`:

```json
{
  "Servers": {
    "1": {
      "Name": "lifeplatform (local)",
      "Group": "Servers",
      "Host": "postgres",
      "Port": 5432,
      "MaintenanceDB": "lifeplatform",
      "Username": "postgres",
      "SSLMode": "prefer",
      "Comment": "Connect as postgres, not app_role: app_role is NOSUPERUSER NOBYPASSRLS, so RLS applies and every tenant-scoped table looks empty until app.current_tenant_id is set. Use app_role only when deliberately verifying isolation."
    }
  }
}
```

and mount it in the `pgadmin` service:

```yaml
    volumes:
      - ./pgadmin/servers.json:/pgadmin4/servers.json:ro
```

- [ ] **Step 5: Write `docs/09-local-development.md`**

It must contain, in order: the prerequisite list (Docker, Node, the VS Code JDK — and an explicit "do NOT install Postgres or Keycloak natively; they run as containers and a host install collides on 5432/8080"); the startup sequence; the reset sequence; the console URLs and credentials table; and the two gotchas below stated plainly rather than left to be rediscovered.

```markdown
## Startup

```bash
cd infra && docker compose up -d postgres keycloak minio redis mock-mobile-money mailpit && cd ..
scripts/migrate.sh local          # nothing applies schema automatically -- see below
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -o spring-boot:run -Dspring-boot.run.profiles=local
scripts/seed-dev-data.sh          # in a second terminal, once the app is up
```

## Two things that will otherwise cost you an afternoon

**1. Migrations do not run automatically.** `pom.xml` contains neither Flyway nor Liquibase, so
`docker compose up` on a fresh volume leaves an entirely empty database behind a running app, and
every request fails on a missing relation — an error that names nothing about the real cause.
`scripts/migrate.sh local` is the only path. It is also NOT idempotent: re-running it against an
already-migrated database fails on plain `CREATE TABLE`. To start clean, drop the volume
(`docker compose --profile tools down -v`), bring it back up, and re-migrate.

**2. Run the application on the HOST, not as the compose `app` service, whenever a browser is
involved.** `application.yml` defaults the Keycloak issuers to `http://localhost:8081/realms/...`,
which is what your browser and Keycloak both use. The compose `app` service overrides them to
`http://keycloak:8080/...` for in-network use. Run the app in Docker and the browser is redirected
to `localhost:8081`, Keycloak stamps the token `iss: http://localhost:8081/...`, and the app
rejects it because it expects `keycloak:8080` — an opaque 401 with a correct-looking login. Running
on the host makes every URL agree, and matches this project's standing rule against running Maven
in Docker.

## Consoles

| Service | URL | Credentials |
|---|---|---|
| Application API | http://localhost:8080 | bearer token from Keycloak |
| Keycloak admin | http://localhost:8081 | `admin` / `devadmin` |
| pgAdmin | http://localhost:5050 | `dev@nlolo-lifeplatform.tz` / `devadmin` (server pre-registered) |
| Mailpit (outbound mail) | http://localhost:8025 | none |
| MinIO console | http://localhost:9001 | `minioadmin` / `minioadmin` |
| WireMock (mobile money) | http://localhost:8082 | none |

pgAdmin's registered connection uses the `postgres` superuser deliberately: `app_role` is
`NOSUPERUSER NOBYPASSRLS`, so browsing as it shows **zero rows** in every tenant-scoped table
until you `SET app.current_tenant_id`. That is tenant isolation working, not a broken database.
```

Start `docs/09-local-development.md` from that content, expanding the prerequisite and reset
sections around it.

- [ ] **Step 6: Commit**

`migrate.sh` is production tooling, so run the full suite — Global Constraints trigger 1.

```bash
./mvnw -B -o test
git add scripts/migrate.sh docs/09-local-development.md infra/
git commit -m "feat: scripts/migrate.sh local mode, pgAdmin pre-registration, and a local-development runbook"
```

---

### Task 8: Keycloak claim mappers, test users, and redirect URIs

**Files:**
- Modify: `keycloak/customers-realm.json`, `keycloak/agents-realm.json`, `keycloak/staff-realm.json`, `keycloak/regulators-realm.json`, `keycloak/README.md`

**Interfaces:**
- Produces: realms whose issued tokens carry `tenant_id` (all four) and `party_id` (customers, agents), and nine users Task 9's seeder authenticates as.

- [ ] **Step 1: Confirm the gap before changing anything**

```bash
grep -c "protocolMappers" keycloak/*.json      # expect 0 for all four
grep -c '"users"' keycloak/*.json              # expect 0 for all four
```
Report the actual output. Then read `TenantContextFilter` and confirm a missing `tenant_id` is a 403 with `errorCode: TENANT_CLAIM_MISSING`, so the consequence of the gap is precisely stated in the task report rather than paraphrased.

- [ ] **Step 2: Add the two mappers to the `lifeplatform-app` client**

For each realm, add to the client's `protocolMappers` array. `tenant_id` goes in all four; `party_id` **only** in `customers` and `agents` — staff bypass ownership checks by role and no code reads `party_id` for them, and regulators own no objects.

```json
{
  "name": "tenant_id",
  "protocol": "openid-connect",
  "protocolMapper": "oidc-usermodel-attribute-mapper",
  "consentRequired": false,
  "config": {
    "user.attribute": "tenant_id",
    "claim.name": "tenant_id",
    "jsonType.label": "String",
    "id.token.claim": "true",
    "access.token.claim": "true",
    "userinfo.token.claim": "true"
  }
}
```

The `party_id` mapper is identical with `tenant_id` replaced by `party_id` throughout. `access.token.claim` must be `true` — the backend reads the access token, not the ID token.

- [ ] **Step 3: Add the nine users**

Every user gets a `tenant_id` attribute set to the single well-known dev tenant
`11111111-1111-1111-1111-111111111111` — the same UUID `regreporting/V2` already seeds its
placeholder return definition against, so seeded reference and reporting data line up. This needs
no coordination with any table: **the platform has no tenant directory** (established in M10), so a
tenant is simply a UUID appearing in RLS-scoped rows.

`party_id` is deliberately left **absent** here and set by Task 9's seeder via the Keycloak Admin
API, because a party's UUID is generated by `POST /parties/individuals` and cannot be known when
this file is written.

| Realm | Username | Realm roles | Why this user exists |
|---|---|---|---|
| customers | `customer.owner` | — | owns policies, claims, a loan |
| customers | `customer.other` | — | owns **nothing** — the only way to see an ownership check actually deny |
| agents | `agent.senior` | — | supervisor in the hierarchy |
| agents | `agent.junior` | — | subordinate, so the hierarchy walk is exercised |
| staff | `staff.underwriter` | `UNDERWRITER` | records the underwriting decision that auto-issues a policy |
| staff | `staff.assessor` | `CLAIMS_ASSESSOR` | assesses a claim |
| staff | `staff.manager` | `CLAIMS_MANAGER` | approves settlement — **must be a different user** than the assessor, because claims enforces separation of duties on the persisted assessor identity |
| staff | `staff.finance` | `FINANCE_OFFICER` | onboards agents, reads GL and regulatory returns |
| regulators | `regulator.tira` | `TIRA_READ_ONLY` | the read-only regulator portal |

Each user entry follows this shape (password is a dev-only value, `temporary: false` so no
forced reset breaks the seeder's password grant):

```json
{
  "username": "customer.owner",
  "enabled": true,
  "emailVerified": true,
  "email": "customer.owner@example.tz",
  "firstName": "Amina",
  "lastName": "Owner",
  "attributes": { "tenant_id": ["11111111-1111-1111-1111-111111111111"] },
  "credentials": [{ "type": "password", "value": "devpassword", "temporary": false }],
  "realmRoles": []
}
```

`ADMIN` is deliberately not among the staff users: `FINANCE_OFFICER` reaches every finance-gated
endpoint, and a dev `ADMIN` account invites using it as a bypass instead of fixing a real gate.

- [ ] **Step 4: Replace the wildcard redirect URIs**

Each realm's `lifeplatform-app` client currently has `"redirectUris": ["*"]`. Replace with:

```json
  "redirectUris": ["http://localhost:3000/*", "http://localhost:8080/*"],
  "webOrigins": ["http://localhost:3000"],
```

`localhost:3000` is M12's portal; `localhost:8080` covers direct API-side flows. The client stays
`"publicClient": false` — the Next.js BFF exchanges the code server-side, so the secret never
reaches a browser and no public client is needed.

- [ ] **Step 5: Document it**

Extend `keycloak/README.md`: the two mappers and why only two (enumerate the real read sites —
`tenant_id` at 1, `party_id` at 10 — and note that the README's own former mention of
`agentOfRecord` was wrong: nothing in `src/main/java` reads it); the nine users and the reason each
exists; the dev tenant UUID; that `party_id` is populated by the seeder, not this file; and that
every password here is dev-only, alongside the existing `dev-secret-*` note.

- [ ] **Step 6: Verify the realms still import, then commit**

A malformed realm JSON fails Keycloak startup, so prove the import rather than assuming it:

```bash
cd infra && docker compose down -v && docker compose up -d postgres keycloak && cd ..
docker compose -f infra/docker-compose.yml logs keycloak | grep -E "imported|ERROR"
```
Expected: four `Realm '<name>' imported` lines, no ERROR. Then confirm a real token now carries
the claims — this is the whole point of the task:

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/realms/staff/protocol/openid-connect/token \
  -d grant_type=password -d client_id=lifeplatform-app -d client_secret=dev-secret-staff \
  -d username=staff.finance -d password=devpassword | sed 's/.*"access_token":"\([^"]*\)".*/\1/')
echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null | tr ',' '\n' | grep -i "tenant_id\|realm_access"
```
Expected: a `tenant_id` claim with the dev UUID. **Read the client secret from the realm JSON
rather than assuming `dev-secret-staff`** — grep it first. No test suite change, so no Maven run.

```bash
git add keycloak/
git commit -m "fix: Keycloak realms issue tenant_id/party_id claims and ship test users"
```

---

### Task 9: The demo-data seeder

**Files:**
- Create: `scripts/seed-dev-data.sh`

**Interfaces:**
- Consumes: Task 7's `migrate.sh local`, Task 8's users and mappers, Task 3's evidence-download endpoint.
- Produces: a populated local database, and the platform's first end-to-end proof that a real Keycloak token works.

- [ ] **Step 1: Understand the event chain before writing anything**

Read `src/main/java/tz/co/nlolo/lifeplatform/policy/application/UnderwritingDecisionEventListener.java`. A policy is **not** created by hand: an accepted underwriting decision publishes `UnderwritingDecisionMade`, which auto-issues the policy, which cascades into `billing` (schedule + invoices), `distribution` (commission accrual), `reinsurance` (cession), `finaccounting` (journal entries) and `regreporting` (four projections). The seeder drives that real chain rather than inserting rows, because a hand-inserted `policy` row has no invoices and no premium due, and reads as a broken frontend rather than as thin seed data.

`POST /policies/manual-issue` exists but needs a `productVersionId` **and** an `underwritingCaseId` anyway, so the natural path is both more realistic and no more work.

- [ ] **Step 2: Write the script's token and guard scaffolding**

```bash
#!/usr/bin/env bash
# Seeds a local stack with coherent demo data by driving the REAL HTTP API -- never SQL inserts.
#
# Why HTTP: this platform is event-driven end to end. One accepted underwriting decision cascades
# into policy issuance, a billing schedule and invoices, a commission accrual, a reinsurance
# cession, GL journal entries and four reporting projections. Inserting a policy row directly
# produces none of that, and the resulting "policy with no invoices" looks like a frontend bug.
#
# It is also the only artefact on this platform that proves a REAL Keycloak-issued token works
# against real endpoints -- every test fabricates its own JWT, so a broken claim mapper is
# invisible to the whole suite. If this script 403s at its first call, the mappers are wrong.
set -euo pipefail

KEYCLOAK="${KEYCLOAK_URL:-http://localhost:8081}"
API="${API_URL:-http://localhost:8080}"
DEV_TENANT="11111111-1111-1111-1111-111111111111"

token_for() {   # token_for <realm> <username> <client-secret>
  curl -sf -X POST "$KEYCLOAK/realms/$1/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=lifeplatform-app -d client_secret="$3" \
    -d username="$2" -d password=devpassword \
  | sed 's/.*"access_token":"\([^"]*\)".*/\1/'
}

api() {         # api <token> <method> <path> [json-body]
  local token="$1" method="$2" path="$3" body="${4:-}"
  if [ -n "$body" ]; then
    curl -sf -X "$method" "$API$path" -H "Authorization: Bearer $token" \
      -H 'Content-Type: application/json' -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" -d "$body"
  else
    curl -sf -X "$method" "$API$path" -H "Authorization: Bearer $token"
  fi
}

# Not idempotent on purpose: re-running would create a second set of parties and policies, and
# silently doubling seed data is worse than refusing. Detect and refuse.
if api "$(token_for staff staff.finance "$STAFF_SECRET")" GET "/products" | grep -q '"productName":"Demo Term Life"'; then
  echo "Already seeded (found the demo product). Reset with: docker compose --profile tools down -v" >&2
  exit 1
fi
```

Read each realm's client secret out of the realm JSON rather than hardcoding it:

```bash
STAFF_SECRET=$(grep -o '"secret" *: *"[^"]*"' keycloak/staff-realm.json | head -1 | cut -d'"' -f4)
```
and the same for `customers` and `agents`.

- [ ] **Step 3: Drive the chain**

In order, capturing each generated id:

1. **Product + version** (`staff.underwriter` or `staff.finance`, both `REALM_STAFF`):
   `POST /products` → `productId`; `POST /products/{productId}/versions` → `productVersionId`.
2. **Parties** (`staff.finance`): two `POST /parties/individuals` with
   `{"fullName":"Amina Owner","dateOfBirth":"1990-04-12","contactInfo":{"phoneNumber":"+255712345678","email":"customer.owner@example.tz"}}`
   → `ownerPartyId`, and a second for `customer.other`. Phone MUST match `^\+255\d{9}$`.
3. **Agents** (`staff.finance`, gated `FINANCE_OFFICER`): `POST /agents` twice → `seniorAgentId`,
   `juniorAgentId` with the senior as supervisor.
4. **Write `party_id` back into Keycloak.** This closes the loop Task 8 deliberately left open —
   the API generates party UUIDs, so the claim can only be set now. Obtain an admin token from the
   `master` realm (`admin`/`devadmin`, client `admin-cli`), then `PUT` each user's attributes:
   ```bash
   ADMIN_TOKEN=$(curl -sf -X POST "$KEYCLOAK/realms/master/protocol/openid-connect/token" \
     -d grant_type=password -d client_id=admin-cli -d username=admin -d password=devadmin \
     | sed 's/.*"access_token":"\([^"]*\)".*/\1/')
   USER_ID=$(curl -sf "$KEYCLOAK/admin/realms/customers/users?username=customer.owner" \
     -H "Authorization: Bearer $ADMIN_TOKEN" | sed 's/.*"id":"\([^"]*\)".*/\1/')
   curl -sf -X PUT "$KEYCLOAK/admin/realms/customers/users/$USER_ID" \
     -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
     -d "{\"attributes\":{\"tenant_id\":[\"$DEV_TENANT\"],\"party_id\":[\"$ownerPartyId\"]}}"
   ```
   Repeat for `customer.other`, `agent.senior` and `agent.junior` (agents' `party_id` must be the
   party their `agent` row points at). **A `PUT` replaces the whole attribute map — always send
   `tenant_id` alongside `party_id` or you will delete it and re-break every request.**
5. **Underwriting → auto-issue**: `POST /underwriting/cases` (agent or staff) → `caseId`;
   `POST /underwriting/cases/{caseId}/assessments` as `staff.underwriter` with an accept decision.
   Then poll `GET /policies` until the auto-issued policy appears — it arrives via an
   `AFTER_COMMIT` listener, so it is not visible in the assessment response.
6. **Collect a premium**: `GET /policies/{policyNumber}/invoices` → pick the first;
   `POST /invoices/{invoiceId}/payment-request` (staff/agent, `Idempotency-Key` **required and
   genuinely enforced** on this endpoint). WireMock confirms, and `billing.PremiumCollected`
   follows.
7. **Claim, with evidence**: `POST /claims` as `customer.owner` (`Idempotency-Key` required);
   `POST /claims/{claimId}/evidence` as multipart with a small real JPEG so Task 3's download
   endpoint has genuine bytes, a real `image/jpeg` content type and a real filename to serve;
   `POST /claims/{claimId}/assessments` as `staff.assessor`; then
   `POST /claims/{claimId}/settlement-decision` as `staff.manager` with `approved=true` and an
   `Idempotency-Key`. Assessor and manager MUST be different users — separation of duties is
   enforced on the persisted assessor identity.
8. **Policy loan**: `POST /policies/{policyNumber}/loans` as `customer.owner`.

Echo each created id as it goes, and finish with a summary block naming the policy number, claim
id and document ref, so a developer can paste them straight into a browser or pgAdmin.

- [ ] **Step 4: Run it end to end against a clean stack**

```bash
cd infra && docker compose down -v && docker compose up -d postgres keycloak minio redis mock-mobile-money mailpit && cd ..
scripts/migrate.sh local
./mvnw -B -o spring-boot:run -Dspring-boot.run.profiles=local   # separate terminal, leave running
scripts/seed-dev-data.sh
```
Expected: completes with the summary block. **If it 403s on the first API call, Task 8's mappers
are wrong — that is the failure this script exists to surface, so diagnose it rather than working
around it with a fabricated token.**

- [ ] **Step 5: Verify the data landed, through both surfaces**

```bash
docker compose -f infra/docker-compose.yml exec -T postgres psql -U postgres -d lifeplatform -c \
  "SELECT 'parties' t, count(*) FROM party.party UNION ALL SELECT 'policies', count(*) FROM policy.policy UNION ALL SELECT 'invoices', count(*) FROM billing.premium_invoice UNION ALL SELECT 'claims', count(*) FROM claims.claim UNION ALL SELECT 'documents', count(*) FROM document.document_record UNION ALL SELECT 'gl_postings', count(*) FROM finaccounting.gl_posting;"
```
Every count must be non-zero — the `gl_posting` and `premium_invoice` counts are the ones that
prove the event cascade ran rather than just the direct writes. Then download the seeded evidence
over HTTP as `customer.owner` and confirm the bytes match the JPEG that was uploaded, which
exercises Task 3's endpoint through a real token for the first time.

- [ ] **Step 6: Commit**

```bash
git add scripts/seed-dev-data.sh
git commit -m "feat: demo-data seeder driving the real event chain through real Keycloak tokens"
```

---

### Task 10: Full verification

- [ ] **Step 1: Full clean verify**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -o clean verify
```
Expected `BUILD SUCCESS`. Aggregate the real counts rather than trusting the log tail:

```bash
grep -h "Tests run:" target/surefire-reports/*.txt | awk -F'[:,]' '{t+=$2; f+=$4; e+=$6} END {print "Total:", t, "Failures:", f, "Errors:", e}'
```
Baseline was 642. Confirm `ModularityTests`, `NoCircularDependencyTest`, `NoCrossModuleJoinTest`,
`AppRolePrivilegesIntegrationTest` and `RowLevelSecurityIntegrationTest` actually ran by grepping
the surefire reports for their names — do not assume `clean verify` covered them. Also confirm the
built jar is real (non-trivial size, `BOOT-INF/` present), since this platform has previously
shipped an empty jar that passed every test.

- [ ] **Step 2: Confirm the scope boundaries held**

```bash
echo "=== document must not depend on any module that depends on it ==="
grep -rn "import tz.co.nlolo.lifeplatform.\(claims\|party\|policy\|underwriting\)\." \
  src/main/java/tz/co/nlolo/lifeplatform/document/ || echo "clean"
echo "=== no allowedDependencies added to document or refdata ==="
cat src/main/java/tz/co/nlolo/lifeplatform/document/package-info.java src/main/java/tz/co/nlolo/lifeplatform/refdata/package-info.java
echo "=== the bundled spec is gone and nothing live references it ==="
ls api/openapi/openapi-regreporting-document-refdata.yaml 2>&1 || echo "deleted, correct"
grep -rn "openapi-regreporting-document-refdata" --include=*.java --include=*.yaml --include=*.sh . | grep -v "^./docs/superpowers/" || echo "only historical doc references remain, correct"
echo "=== no upload endpoint was added to DocumentController ==="
grep -n "PostMapping\|PutMapping" src/main/java/tz/co/nlolo/lifeplatform/document/infrastructure/DocumentController.java || echo "clean -- read-only as designed"
```
Any hit beyond the stated exceptions is a scope breach and must be reported prominently.

- [ ] **Step 3: Re-prove the two security invariants empirically**

Both are the kind of check that passes for the wrong reason if taken on trust:

1. **The IDOR check still bites.** Delete the `ownerContext` comparison in
   `ClaimEvidenceController.downloadEvidence`, run `-Dtest=ClaimEvidenceDownloadTest`, confirm the
   cross-claim test FAILS, restore, confirm it passes. Quote both outcomes.
2. **The allowlist still denies.** With the stack seeded, request
   `GET /reference-codes/TZ_BASE_PREMIUM_RATE_PER_MILLE` with a real `customer.owner` token and
   confirm 404; with `staff.finance`, confirm 200. This exercises the allowlist through a genuine
   Keycloak token rather than a fabricated one.

- [ ] **Step 4: Acceptance confirmation**

State plainly, each backed by a named test or command output:
1. Claim evidence is downloadable and gated by both checks.
2. Staff can read any in-tenant document and its metadata; customers and agents cannot.
3. `refdata` is readable per realm, fail-closed, with denied and unknown keys indistinguishable.
4. Both new OpenAPI documents parse standalone and have contract coverage.
5. A real Keycloak token reaches real endpoints — the seeder completed.
6. A developer can go from a clean checkout to a populated, browsable local stack using only
   `docs/09-local-development.md`.
7. **Explicitly NOT claimed:** party/policy document listing (no producer exists), document
   deletion, `refdata` writes, streaming downloads, production Keycloak configuration, and
   Flyway — every one recorded in the spec's §8 and §14.

- [ ] **Step 5: Report and stop**

Do not merge. Report the aggregate count, the acceptance mapping, every deviation, and every
deferred item, so the final whole-branch review has an accurate baseline.

---

## Self-Review Notes

**Judgment calls flagged for the final review — each a place this plan chose a side.**

1. **`DocumentResponses` lives in `document.infrastructure` and is called from `claims`.** Task 3
   Step 4 makes this conditional on `ModularityTests` accepting a cross-module `infrastructure`
   reference, with duplication as the explicit fallback. If the reviewer believes the shared helper
   weakens the module boundary even when Modulith permits it, that is a legitimate objection —
   `claims` already depends on `document::api`, but `infrastructure` is not a named interface.
   (`DocumentNotFoundException` is not affected: it lives in `document.api`, which `claims`
   genuinely declares.)

0. **This plan's own first draft assumed a bare `NoSuchElementException` returned 404.** It does
   not — `GlobalExceptionHandler` never maps it, so every "not found" path in Tasks 3–5 would have
   returned **500**, and the allowlist's whole indistinguishability property would have been built
   on a false premise. Caught in plan self-review by checking the handler rather than trusting the
   existing `DocumentApiImpl` comment, which describes the intent correctly but whose exception
   choice never reached the REST boundary. Task 1 now creates two typed exceptions and two
   handlers following the platform's ten-handler precedent. Worth flagging to the final review
   because it means **the pre-existing `document` module has been returning 500s for
   missing/cross-tenant refs all along** — invisible until now only because nothing routed to it.
2. **Endpoint 1's path lives in `openapi-document.yaml`, not `openapi-claims.yaml`.** It is a
   `/claims/...` URL documented in the document module's spec. Chosen so one contract test covers
   all three document responses; a reviewer may reasonably prefer it beside the other two
   `/claims/{claimId}/evidence` operations.
3. **`GET /documents/{ref}` is gated on bare `REALM_STAFF`** with no fine-grained role, unlike
   every other staff endpoint on the platform (which all add `FINANCE_OFFICER or ADMIN`). Rationale
   in the controller javadoc: a claims assessor must read claim attachments. This is a deliberate
   inconsistency with platform precedent.
4. **An unknown `codeSetKey` now returns 404, changing the Phase 0 draft's empty-200.** Forced by
   the allowlist — a denied 404 against an unknown 200 would leak which keys exist.
5. **`document/V2`'s columns are nullable with no backfill.** Every pre-existing document takes the
   fallback path. Task 3's null-tolerance test is the only thing covering it.
6. **The dev tenant UUID is `11111111-1111-1111-1111-111111111111`**, reused from
   `regreporting/V2`'s seed so reporting data lines up. It is a magic value in four realm JSONs.
7. **`party_id` is set by the seeder via the Keycloak Admin API**, not declared in the realm JSON,
   because the API generates party UUIDs. This makes the realms alone insufficient for a working
   login — the seeder is mandatory, which is a real coupling a reviewer should weigh.
8. **The seeder refuses to re-run rather than being idempotent.** Detecting the demo product and
   exiting is cruder than upserting, and means the only reset path is dropping the volume.
9. **`ADMIN` is deliberately absent from the staff test users**, so any endpoint reachable only by
   `ADMIN` cannot be exercised locally. If one exists, this is a gap.
10. **Flyway is not adopted**, though `migrate.sh` proposed it in M1 and `staging`/`production`
    share the same non-idempotent script. Recorded as overdue in the spec's §14; a reviewer may
    argue it belongs here rather than deferred again.
11. **`redirectUris` is narrowed to `localhost:3000`/`localhost:8080`** before M12 exists, so the
    portal's real port is assumed now.
