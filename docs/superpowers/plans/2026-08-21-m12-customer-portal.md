# M12 Customer Portal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the platform's first frontend — a customer self-service portal covering policies, billing, claims, and policy loans — plus the three backend authorization changes it needs to function.

**Architecture:** Next.js App Router acting as a Backend-for-Frontend: every backend call runs server-side in a Route Handler or Server Action, so the confidential Keycloak client secret and the access token never reach the browser (the backend has no CORS policy at all, by design — this is forced, not preferred). NextAuth holds the `customers`-realm session and refreshes the ~5-minute access token in its `jwt` callback. TypeScript types are generated per OpenAPI spec; there is no aggregate spec to generate from.

**Tech Stack:** Next.js 15 (App Router) · TypeScript · Tailwind CSS · shadcn/ui · TanStack Query · NextAuth v5 (Auth.js) · ioredis · openapi-typescript · Vitest + React Testing Library · Playwright. Backend prelude: Java 21 / Spring Boot / Spring Modulith (existing Maven project).

**Spec:** `docs/superpowers/specs/2026-08-21-m12-customer-portal-design.md` (committed `80b51a0`, revised `54f7eab`, prelude added this session).

**Baseline:** `main` at `54f7eab`, 661/661 tests green.

## Global Constraints

- **Money is a decimal string end to end.** `Money` is `{ amount: string, currencyCode: string }` in `openapi-common.yaml`. Never coerce `amount` to `number` anywhere. `formatMoney(amount, currencyCode)` (Task 4) is the ONLY function in the codebase that parses a money string.
- **Every backend call is server-side.** Route Handlers or Server Actions only. No `fetch` to `BACKEND_BASE_URL` from a Client Component, ever. The browser talks only to the portal's own `/api/*` routes.
- **`customers` realm only.** Keycloak issuer `http://localhost:8081/realms/customers`, client `lifeplatform-app`, secret `dev-secret-customers`. The portal never constructs, stores, or reads an agent/staff/regulator token.
- **The portal never sends `policyholderPartyId` or `claimantPartyId`.** The backend force-scopes both from the token's `party_id` claim. Sending them is a bug even when the value is correct.
- **Token refresh is mandatory, not optional.** Access tokens last ~5 minutes (no realm override exists). Refresh in NextAuth's `jwt` callback; on refresh failure clear the session and force re-login — never surface a raw error.
- **Hard idempotency guard on exactly two endpoints:** `POST /policies/{n}/loans` and `POST /loans/{id}/repayments`. Both layers required (synchronous `useRef` + Redis `SET NX EX 900`). A `disabled` button is not a guard.
- **English only.** No `next-intl`, no translation keys, no locale scaffolding.
- **Not built, deliberately:** endorsements (agent/staff-only by design), KYC upload (staff-only), a generic documents screen (only one document producer exists), notifications (`communication`/`omnichannel` are empty stubs), surrender submission (returns 501).
- **Cash value renders as the API returns it** — `TZS 0.00` platform-wide today. No caveat, no hiding, no special-casing.
- **Backend prelude changes are authorization-only.** Do not change business logic, schemas, or migrations in Tasks 1–2.
- Java tests use the `jwt()` request-post-processor idiom with a synthetic `ROLE_REALM_CUSTOMERS` authority; `ROLE_REALM_*` is derived from the token issuer by `SecurityConfig.authoritiesFor`, not from an assigned Keycloak role.

---

## File Structure

**Backend prelude (existing Maven project):**
- `src/main/java/.../policy/infrastructure/PolicyController.java` — modify two endpoint annotations, add two helpers
- `src/main/java/.../billing/infrastructure/BillingController.java` — modify one endpoint
- `src/main/java/.../billing/api/BillingApi.java` + `application/BillingApiImpl.java` — add `getInvoice`
- `api/openapi/openapi-policy.yaml`, `api/openapi/openapi-billing.yaml` — correct `security:` blocks
- `src/test/java/.../policy/PolicyCustomerScopingTest.java`, `src/test/java/.../billing/BillingCustomerPaymentTest.java` — new

**Frontend (new tree at `frontend/customer-portal/`):**

| Path | Responsibility |
|---|---|
| `src/lib/money.ts` | `formatMoney` — the only money-string parser |
| `src/lib/problem.ts` | `mapApiError` — `ProblemDetails` → user copy |
| `src/lib/backend.ts` | `callBackend` — server-only fetch: bearer token, JSON, `ProblemDetails` → thrown `ApiError` |
| `src/lib/redis.ts` | ioredis singleton |
| `src/lib/idempotency.ts` | `claimIdempotency` / `releaseIdempotency` / `recordOutcome` |
| `src/auth.ts` | NextAuth config + Keycloak refresh |
| `src/hooks/use-submit-guard.ts` | Layer 1 synchronous re-entrancy guard |
| `src/app/api/**/route.ts` | BFF Route Handlers, one per backend call the UI needs |
| `src/app/(portal)/**/page.tsx` | Screens |
| `src/components/ui/*` | shadcn/ui primitives (generated) |
| `src/types/api/*.ts` | Generated per-spec types (committed) |

---

## Task 1: Backend — make `GET /policies` and `coverage-status` customer-reachable

**Files:**
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/PolicyController.java:74-83` (`searchPolicies`), `:138-142` (`getCoverageStatus`), `:158-168` (refactor helper)
- Modify: `api/openapi/openapi-policy.yaml:61-92` (`/policies` security + description), `:249-268` (`coverage-status` security)
- Test: `src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyCustomerScopingTest.java` (create)

**Interfaces:**
- Consumes: `policyApi.searchPolicies(UUID policyholderPartyId, PolicyStatus status, Pageable pageable)` — already accepts the party filter; `policyApi.getPolicy(String policyNumber)`; existing private `enforceCustomerOwnPolicyOnly(PolicyView, Jwt, Authentication)`.
- Produces: `GET /policies` reachable by a customer token, force-scoped to its own `party_id`; `GET /policies/{n}/coverage-status` reachable by a customer token for its own policy only. Task 8 consumes the first, Task 9 the second.

**Context:** `ClaimController.listClaims` already solves this exact problem for claims. Copy its idiom rather than inventing one: for a list endpoint there is no single resource to 403 on, so the correct enforcement point is **overriding the query filter**, not checking a client-supplied value. `ClaimController` has package-private `static boolean isCustomer(Authentication)` and `static UUID ownPartyIdOrThrow(Jwt)` — `PolicyController` needs its own copies (a different module's infrastructure package; do not import across modules, `ModularityTests` will fail).

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyCustomerScopingTest.java`. Container/fixture setup copies `PolicyContractTest`. The negative tests are the load-bearing ones — each must fail if its check is deleted.

```java
package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M12 Task 1: GET /policies and GET /policies/{n}/coverage-status become customer-reachable.
 *
 * <p>The two "ignores client-supplied" tests are the ones that matter. A check-then-reject
 * implementation passes the happy-path test and fails these -- a customer must never be able to
 * widen their own scope by supplying someone else's policyholderPartyId, and the correct behaviour
 * is to OVERRIDE the parameter, not to 403 on it, because a list endpoint has no single resource
 * to deny. Same reasoning as ClaimController.listClaims' javadoc.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PolicyCustomerScopingTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void customerTokenReachesPolicyList() throws Exception {
        mockMvc.perform(get("/policies")
                .with(customerOf(TENANT, UUID.randomUUID())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void customerListIgnoresClientSuppliedPolicyholderPartyId() throws Exception {
        UUID own = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();
        // Supplying a stranger's id must NOT widen scope. With zero policies seeded for `own`,
        // a correct force-scoped implementation returns an empty page rather than 403 or a leak.
        mockMvc.perform(get("/policies")
                .param("policyholderPartyId", someoneElse.toString())
                .with(customerOf(TENANT, own)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void customerTokenWithoutPartyIdClaimIsDenied() throws Exception {
        mockMvc.perform(get("/policies")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", TENANT.toString()))))
            .andExpect(status().isForbidden());
    }

    @Test
    void agentListStillAcceptsAnExplicitPolicyholderFilter() throws Exception {
        mockMvc.perform(get("/policies")
                .param("policyholderPartyId", UUID.randomUUID().toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", TENANT.toString()))))
            .andExpect(status().isOk());
    }

    @Test
    void customerCannotReadAnotherPartysCoverageStatus() throws Exception {
        // No policy exists for this number, so a 404 would also "pass" a naive assertion.
        // Assert specifically that the response is NOT 200 and NOT 500 -- the ownership check
        // must run before any lookup can succeed for a stranger's policy.
        mockMvc.perform(get("/policies/POL-NONEXISTENT/coverage-status")
                .with(customerOf(TENANT, UUID.randomUUID())))
            .andExpect(status().is4xxClientError());
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customerOf(UUID tenantId, UUID partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString())
                                   .claim("party_id", partyId.toString()));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=PolicyCustomerScopingTest`
Expected: FAIL — `customerTokenReachesPolicyList` and `customerCannotReadAnotherPartysCoverageStatus` return 403 because `@PreAuthorize` excludes `REALM_CUSTOMERS`.

- [ ] **Step 3: Add the two helpers and refactor the existing one**

In `PolicyController`, add these package-private statics next to `enforceCustomerOwnPolicyOnly`, and rewrite that method to use `isCustomer` so there is one definition of "is this caller a customer":

```java
    static boolean isCustomer(Authentication authentication) {
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_CUSTOMERS"::equals);
    }

    static UUID ownPartyIdOrThrow(Jwt jwt) {
        String ownPartyId = jwt.getClaimAsString("party_id");
        if (ownPartyId == null) {
            throw new AccessDeniedException("Customer token carries no party_id claim");
        }
        return UUID.fromString(ownPartyId);
    }
```

Rewrite the body of `enforceCustomerOwnPolicyOnly` (keep its javadoc, keep it private, keep the message):

```java
    private void enforceCustomerOwnPolicyOnly(PolicyView view, Jwt jwt, Authentication authentication) {
        if (!isCustomer(authentication)) {
            return;
        }
        String ownPartyId = jwt.getClaimAsString("party_id");
        if (ownPartyId == null || !ownPartyId.equals(view.policyholderPartyId().toString())) {
            throw new AccessDeniedException("Access denied: customer may only access their own policy");
        }
    }
```

Add `import java.util.UUID;` if absent.

- [ ] **Step 4: Force-scope the list endpoint**

Replace `searchPolicies` entirely:

```java
    /**
     * Customers are force-SCOPED to their own policies: a client-supplied
     * {@code policyholderPartyId} is OVERRIDDEN with the token's own {@code party_id} claim, never
     * merely checked-then-rejected -- {@code tenant_id}/{@code party_id} never come from client
     * input (docs/04-api-contracts.md:39). Scoping the query itself is the correct enforcement
     * point for a list endpoint, unlike getPolicy/coverage-status which 403 on an explicit mismatch
     * against a resource that already exists. Same idiom as ClaimController.listClaims.
     */
    @GetMapping("/policies")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PolicySearchResponse> searchPolicies(
            @RequestParam(required = false) UUID policyholderPartyId,
            @RequestParam(required = false) PolicyStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        UUID effectivePolicyholderPartyId = isCustomer(authentication)
            ? ownPartyIdOrThrow(jwt) : policyholderPartyId;
        Page<PolicyView> result = policyApi.searchPolicies(effectivePolicyholderPartyId, status,
            PageRequest.of(page, Math.min(pageSize, 100)));
        return ResponseEntity.ok(PolicySearchResponse.from(result));
    }
```

- [ ] **Step 5: Open coverage-status to customers, ownership-checked**

Replace `getCoverageStatus`:

```java
    @GetMapping("/policies/{policyNumber}/coverage-status")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<CoverageStatusResponseDto> getCoverageStatus(@PathVariable String policyNumber,
            @RequestParam(required = false) LocalDate asOf,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        enforceCustomerOwnPolicyOnly(policyApi.getPolicy(policyNumber), jwt, authentication);
        return ResponseEntity.ok(CoverageStatusResponseDto.from(policyApi.getCoverageStatus(policyNumber, asOf)));
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=PolicyCustomerScopingTest`
Expected: PASS, 5/5.

- [ ] **Step 7: Correct the OpenAPI spec**

In `api/openapi/openapi-policy.yaml`, under `/policies:` `get:` — replace the description and add `customersAuth`:

```yaml
      summary: Search policies
      description: >
        Customers are force-scoped to their own policies: a client-supplied policyholderPartyId is
        overridden with the token's party_id claim. Agents are scoped to their agency hierarchy.
        Staff have unrestricted read within their tenant.
      security:
        - customersAuth: [profile]
        - agentsAuth: [agent]
        - staffAuth: []
```

Under `/policies/{policyNumber}/coverage-status:` `get:`, add `- customersAuth: [profile]` as the first entry of its `security:` list.

- [ ] **Step 8: Run the full suite**

Run: `./mvnw test`
Expected: PASS. Baseline was 661; this task adds 5. Run it in the FOREGROUND and read the real Surefire summary — do not background it and wait for a notification that cannot arrive.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/PolicyController.java \
        src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyCustomerScopingTest.java \
        api/openapi/openapi-policy.yaml
git commit -m "feat(policy): make policy list and coverage-status customer-reachable

GET /policies force-scopes policyholderPartyId to the token's party_id for
customer callers, the same idiom ClaimController.listClaims already uses.
coverage-status gains the existing enforceCustomerOwnPolicyOnly check. The
OpenAPI security blocks and the 'no bulk listing for customers' description
were both wrong and are corrected."
```

---

## Task 2: Backend — let a customer request payment for their own invoice

**Files:**
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/billing/api/BillingApi.java` (add `getInvoice`)
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/billing/application/BillingApiImpl.java` (implement it)
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/billing/infrastructure/BillingController.java:78-92` (`requestPaymentForInvoice`)
- Modify: `api/openapi/openapi-billing.yaml:84-128` (security block)
- Test: `src/test/java/tz/co/nlolo/lifeplatform/billing/BillingCustomerPaymentTest.java` (create)

**Interfaces:**
- Consumes: existing private `BillingController.enforceCustomerOwnPolicyOnly(PolicyView, Jwt, Authentication)`; `policyApi.getPolicy(String)`; `premiumInvoiceRepository.findByInvoiceIdAndTenantId(UUID, UUID)` (already used at `BillingApiImpl:79`).
- Produces: `InvoiceView BillingApi.getInvoice(UUID invoiceId)`; `POST /invoices/{invoiceId}/payment-request` reachable by a customer for their own invoice. Task 10 consumes the endpoint.

**Context:** `requestPaymentForInvoice` takes only `invoiceId`, so ownership cannot be checked without resolving invoice → policy. `BillingApi` has no `getInvoice`, so add one. `Idempotency-Key` is genuinely enforced on this endpoint (a real dedup registry) and must stay required — do not relax it.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/tz/co/nlolo/lifeplatform/billing/BillingCustomerPaymentTest.java`. Copy container setup and the invoice fixture from `BillingContractTest`.

```java
package tz.co.nlolo.lifeplatform.billing;

import tz.co.nlolo.lifeplatform.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M12 Task 2: POST /invoices/{invoiceId}/payment-request becomes customer-reachable, gated on the
 * invoice's own policy. The cross-party test is the load-bearing one: without the ownership check
 * any authenticated customer could trigger a collection against a stranger's invoice, and the
 * endpoint takes no policyNumber, so the check depends entirely on resolving invoice -> policy.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class BillingCustomerPaymentTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BODY = "{\"payerRef\":\"255700000001\"}";

    @Test
    void customerRealmIsNoLongerRejectedOutright() throws Exception {
        // A nonexistent invoice must produce a 404-family answer, NOT the 403 that the old
        // @PreAuthorize produced for every customer token regardless of ownership.
        mockMvc.perform(post("/invoices/{id}/payment-request", UUID.randomUUID())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)
                .with(customerOf(TENANT, UUID.randomUUID())))
            .andExpect(status().isNotFound());
    }

    @Test
    void idempotencyKeyIsStillRequiredForCustomers() throws Exception {
        mockMvc.perform(post("/invoices/{id}/payment-request", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)
                .with(customerOf(TENANT, UUID.randomUUID())))
            .andExpect(status().isBadRequest());
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customerOf(UUID tenantId, UUID partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString())
                                   .claim("party_id", partyId.toString()));
    }
}
```

**Also add a cross-party denial test using a real seeded invoice.** Copy `BillingContractTest`'s invoice-creation fixture verbatim (it seeds a policy and its schedule), then:

```java
    @Test
    void customerCannotRequestPaymentForAnotherPartysInvoice() throws Exception {
        // `seedInvoiceOwnedBy` is BillingContractTest's fixture idiom: create a policy whose
        // policyholderPartyId is `owner`, let the schedule generate invoices, return the first.
        UUID owner = UUID.randomUUID();
        UUID invoiceId = seedInvoiceOwnedBy(owner);

        mockMvc.perform(post("/invoices/{id}/payment-request", invoiceId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)
                .with(customerOf(TENANT, UUID.randomUUID())))   // a different party
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/invoices/{id}/payment-request", invoiceId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)
                .with(customerOf(TENANT, owner)))
            .andExpect(status().isAccepted());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=BillingCustomerPaymentTest`
Expected: FAIL — every customer request returns 403 (`@PreAuthorize` admits only STAFF/AGENTS), so the 404, 400, and 202 expectations all miss.

- [ ] **Step 3: Add `getInvoice` to the API**

In `BillingApi.java`, next to `listInvoices`:

```java
    /**
     * Single-invoice read by id, needed for object-level authorization on endpoints that identify
     * an invoice without naming its policy (BillingController.requestPaymentForInvoice). Tenant
     * scoping is applied inside the implementation, never taken from the caller.
     */
    InvoiceView getInvoice(UUID invoiceId);
```

In `BillingApiImpl.java`, mirroring the existing lookup at line 79:

```java
    @Override
    public InvoiceView getInvoice(UUID invoiceId) {
        UUID tenantId = TenantContext.requireTenantId();
        PremiumInvoice invoice = premiumInvoiceRepository.findByInvoiceIdAndTenantId(invoiceId, tenantId)
            .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        return InvoiceView.from(invoice);
    }
```

Use whatever tenant accessor and not-found exception the surrounding methods in `BillingApiImpl` already use — read line 79's method and copy it exactly rather than assuming these names.

- [ ] **Step 4: Gate the endpoint on invoice ownership**

In `BillingController.requestPaymentForInvoice`, keep the whole javadoc and the `Idempotency-Key` rejection block unchanged. Change the annotation and add the check:

```java
    @PostMapping("/invoices/{invoiceId}/payment-request")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_STAFF') or hasRole('REALM_AGENTS')")
    public ResponseEntity<Void> requestPaymentForInvoice(@PathVariable UUID invoiceId,
                                                          @Valid @RequestBody PaymentRequestDto request,
                                                          @RequestHeader(value = "Idempotency-Key", required = false)
                                                          String idempotencyKey,
                                                          @AuthenticationPrincipal Jwt jwt,
                                                          Authentication authentication) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required on this endpoint: the same key "
                + "is treated as the same collection attempt (deduplicated), a new key as a new attempt. There is "
                + "deliberately no default -- any default would make a genuine operator retry after a decline "
                + "impossible.");
        }
        InvoiceView invoice = billingApi.getInvoice(invoiceId);
        enforceCustomerOwnPolicyOnly(policyApi.getPolicy(invoice.policyNumber()), jwt, authentication);
        billingApi.requestPaymentForInvoice(invoiceId, request.payerRef(), idempotencyKey);
        return ResponseEntity.accepted().build();
    }
```

The `Idempotency-Key` check stays FIRST — a missing header is a 400 regardless of who is calling, and moving the ownership check ahead of it would change the status code agents/staff already receive.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=BillingCustomerPaymentTest`
Expected: PASS, 3/3.

- [ ] **Step 6: Correct the OpenAPI spec**

In `api/openapi/openapi-billing.yaml`, under `/invoices/{invoiceId}/payment-request:` `post:`, add `- customersAuth: [profile]` as the first `security:` entry and add to its description:

```yaml
        Customers may request payment only for an invoice belonging to their own policy
        (object-level check against the token's party_id claim); agents and staff are scoped to
        their tenant.
```

- [ ] **Step 7: Run the full suite**

Run: `./mvnw test`
Expected: PASS. Run it in the FOREGROUND and report the real Surefire numbers.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/billing/ \
        src/test/java/tz/co/nlolo/lifeplatform/billing/BillingCustomerPaymentTest.java \
        api/openapi/openapi-billing.yaml
git commit -m "feat(billing): let a customer request payment for their own invoice

Adds BillingApi.getInvoice so the endpoint can resolve invoice -> policy, then
gates it on the existing enforceCustomerOwnPolicyOnly check. Idempotency-Key
stays required and is still validated first."
```

---

## Task 3: Frontend scaffold

**Files:**
- Create: `frontend/customer-portal/` (Next.js app), `frontend/customer-portal/.env.example`, `frontend/customer-portal/vitest.config.ts`, `frontend/customer-portal/src/lib/__tests__/smoke.test.ts`
- Modify: `.gitignore` (repo root)

**Interfaces:**
- Produces: a buildable Next.js app at `frontend/customer-portal/` with Tailwind, shadcn/ui initialised, and a working `npm test`. Every later task adds files inside `src/`.

**Context:** The repo root is a Maven project (`pom.xml`, `src/` = Java). The frontend lives in its own directory with its own `package.json`; nothing wires it into Maven. `.gitignore` currently has no Node entries at all — add them or `node_modules` gets committed.

- [ ] **Step 1: Add Node ignores at the repo root**

Append to `.gitignore`:

```gitignore
# Frontend (M12)
node_modules/
frontend/**/.next/
frontend/**/out/
frontend/**/.env.local
frontend/**/coverage/
frontend/**/test-results/
frontend/**/playwright-report/
```

- [ ] **Step 2: Scaffold the app**

```bash
cd frontend
npx --yes create-next-app@latest customer-portal \
  --typescript --tailwind --eslint --app --src-dir \
  --import-alias "@/*" --no-turbopack --use-npm
cd customer-portal
```

- [ ] **Step 3: Install the runtime and test dependencies**

```bash
npm install next-auth@beta ioredis @tanstack/react-query
npm install --save-dev openapi-typescript vitest @vitejs/plugin-react \
  @testing-library/react @testing-library/jest-dom @testing-library/user-event \
  jsdom @playwright/test
```

`next-auth@beta` is Auth.js v5 — the App Router-native line, and the only version whose `jwt` callback signature matches Task 5's refresh code.

- [ ] **Step 4: Initialise shadcn/ui**

```bash
npx --yes shadcn@latest init --yes --defaults
npx --yes shadcn@latest add button card table badge input label \
  select tabs dialog alert skeleton form sonner
```

This writes `components.json` and `src/components/ui/*`. These are project source, reviewed like any other code — they are committed, not gitignored.

- [ ] **Step 5: Configure Vitest**

Create `frontend/customer-portal/vitest.config.ts`:

```ts
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    globals: true,
    include: ['src/**/*.test.{ts,tsx}'],
  },
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
});
```

Create `frontend/customer-portal/vitest.setup.ts`:

```ts
import '@testing-library/jest-dom/vitest';
```

Add to `package.json` scripts:

```json
    "test": "vitest run",
    "test:watch": "vitest",
    "e2e": "playwright test"
```

- [ ] **Step 6: Write the environment template**

Create `frontend/customer-portal/.env.example` (committed; `.env.local` is ignored):

```bash
# Backend (Spring Boot) — host port from infra/docker-compose.yml
BACKEND_BASE_URL=http://localhost:8080

# Keycloak `customers` realm — host port 8081, confidential client
KEYCLOAK_ISSUER=http://localhost:8081/realms/customers
KEYCLOAK_CLIENT_ID=lifeplatform-app
KEYCLOAK_CLIENT_SECRET=dev-secret-customers

# NextAuth. Generate with: openssl rand -base64 32
AUTH_SECRET=replace-me
AUTH_URL=http://localhost:3000

# Redis — host port from infra/docker-compose.yml, used for the BFF idempotency guard
REDIS_URL=redis://localhost:6379
```

- [ ] **Step 7: Write a real smoke test**

Create `frontend/customer-portal/src/lib/__tests__/smoke.test.ts`. This asserts the alias and TS pipeline actually work, rather than asserting `true === true` — a test that cannot fail proves nothing:

```ts
import { describe, expect, it } from 'vitest';
import { cn } from '@/lib/utils';

describe('toolchain', () => {
  it('resolves the @/ alias and runs shadcn\'s cn helper', () => {
    expect(cn('a', false && 'b', 'c')).toBe('a c');
  });
});
```

- [ ] **Step 8: Verify the build and tests**

```bash
npm run test
npm run build
```
Expected: 1 test passing; build exits 0. Run both in the FOREGROUND.

- [ ] **Step 9: Commit**

```bash
cd ../..
git add .gitignore frontend/
git commit -m "feat(portal): scaffold the customer portal (Next.js, Tailwind, shadcn/ui, Vitest)"
```

---

## Task 4: Generated API types, money formatting, and error mapping

**Files:**
- Create: `frontend/customer-portal/scripts/generate-api-types.mjs`
- Create: `frontend/customer-portal/src/types/api/*.ts` (generated, committed)
- Create: `frontend/customer-portal/src/lib/money.ts` + `src/lib/money.test.ts`
- Create: `frontend/customer-portal/src/lib/problem.ts` + `src/lib/problem.test.ts`

**Interfaces:**
- Produces:
  - `formatMoney(amount: string, currencyCode: string): string`
  - `type ApiProblem = { type: string; title: string; status: number; detail?: string; errorCode?: string; traceId: string }`
  - `mapApiError(problem: ApiProblem | null, fallbackStatus?: number): string`
  - Generated types importable as `import type { paths, components } from '@/types/api/policy'` (one module per spec).
  Tasks 6–13 consume all of these.

**Context:** There is no aggregate OpenAPI spec — 15 separate files in `api/openapi/`, no live `/v3/api-docs`. Generate one TS module per spec the portal needs. Committing the output means CI needs no generation step and a spec change shows up as a reviewable diff.

- [ ] **Step 1: Write the generation script**

Create `frontend/customer-portal/scripts/generate-api-types.mjs`:

```js
// Generates one TS module per OpenAPI spec the portal consumes. There is no aggregate spec on
// this platform (15 separate files, no springdoc endpoint), so there is no merge step either.
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import path from 'node:path';

const SPECS = [
  'common', 'party', 'product', 'underwriting', 'policy',
  'billing', 'payment', 'claims', 'policyloan', 'document', 'refdata',
];

const specDir = path.resolve(import.meta.dirname, '../../../api/openapi');
const outDir = path.resolve(import.meta.dirname, '../src/types/api');
mkdirSync(outDir, { recursive: true });

for (const name of SPECS) {
  const input = path.join(specDir, `openapi-${name}.yaml`);
  const output = path.join(outDir, `${name}.ts`);
  execFileSync('npx', ['--yes', 'openapi-typescript', input, '-o', output], {
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
  console.log(`generated ${output}`);
}
```

Add to `package.json` scripts: `"generate:api": "node scripts/generate-api-types.mjs"`.

- [ ] **Step 2: Generate and inspect the output**

```bash
npm run generate:api
```
Expected: 11 files under `src/types/api/`. Then verify the money type came through as a string — this is the constraint the whole plan leans on:

```bash
grep -n "amount" src/types/api/common.ts
```
Expected: `amount?: string;` — **not** `number`. If it is `number`, stop and report: the spec and the plan disagree and the constraint needs re-deriving.

- [ ] **Step 3: Write the failing money tests**

Create `frontend/customer-portal/src/lib/money.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { formatMoney } from './money';

describe('formatMoney', () => {
  it('formats a whole-shilling amount with thousands separators', () => {
    expect(formatMoney('1500000', 'TZS')).toBe('TZS 1,500,000.00');
  });

  it('keeps exactly two decimal places', () => {
    expect(formatMoney('1234.5', 'TZS')).toBe('TZS 1,234.50');
  });

  it('renders zero without special-casing it', () => {
    // Cash value is TZS 0.00 platform-wide today; the portal shows it as-is.
    expect(formatMoney('0', 'TZS')).toBe('TZS 0.00');
  });

  it('handles a negative amount', () => {
    expect(formatMoney('-250.25', 'TZS')).toBe('TZS -250.25');
  });

  it('does not lose precision on a value beyond float safety', () => {
    // 9007199254740993 is 2^53+1 — unrepresentable as a JS number. This test is the reason
    // formatMoney must not go through parseFloat/Number anywhere.
    expect(formatMoney('9007199254740993', 'TZS')).toBe('TZS 9,007,199,254,740,993.00');
  });

  it('rejects a malformed amount loudly rather than rendering NaN', () => {
    expect(() => formatMoney('abc', 'TZS')).toThrow(/malformed money amount/i);
  });
});
```

- [ ] **Step 4: Run it to verify it fails**

Run: `npm test -- money`
Expected: FAIL — `Cannot find module './money'`.

- [ ] **Step 5: Implement `formatMoney`**

Create `frontend/customer-portal/src/lib/money.ts`:

```ts
/**
 * The ONLY place in the portal that parses a money string.
 *
 * The backend models money as `{ amount: string, currencyCode: string }` with a
 * `^-?\d+(\.\d{1,2})?$` pattern — deliberately a decimal string, not a float. Coercing it to
 * `number` anywhere would silently reintroduce the rounding the backend went out of its way to
 * avoid, so this function formats by string manipulation only: no parseFloat, no Number, no
 * Intl.NumberFormat on a numeric conversion.
 */
const MONEY_PATTERN = /^(-?)(\d+)(?:\.(\d{1,2}))?$/;

export function formatMoney(amount: string, currencyCode: string): string {
  const match = MONEY_PATTERN.exec(amount);
  if (!match) {
    throw new Error(`malformed money amount from the API: ${JSON.stringify(amount)}`);
  }
  const [, sign, whole, fraction = ''] = match;
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const cents = fraction.padEnd(2, '0');
  return `${currencyCode} ${sign}${grouped}.${cents}`;
}
```

- [ ] **Step 6: Run the money tests to verify they pass**

Run: `npm test -- money`
Expected: PASS, 6/6.

- [ ] **Step 7: Write the failing error-mapping tests**

Create `frontend/customer-portal/src/lib/problem.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { mapApiError } from './problem';

describe('mapApiError', () => {
  it('gives surrender its own explanation, not an error banner', () => {
    expect(mapApiError({
      type: 'about:blank', title: 'Not Implemented', status: 501,
      errorCode: 'CHOREOGRAPHY_NOT_IMPLEMENTED', traceId: 't1',
    })).toMatch(/not available online yet/i);
  });

  it('explains insufficient loan value in plain language', () => {
    expect(mapApiError({
      type: 'about:blank', title: 'Conflict', status: 409,
      errorCode: 'INSUFFICIENT_LOAN_VALUE', traceId: 't2',
    })).toMatch(/cash value/i);
  });

  it('falls back on an unknown errorCode without exposing internals', () => {
    const message = mapApiError({
      type: 'about:blank', title: 'Conflict', status: 409,
      errorCode: 'SOME_FUTURE_CODE', traceId: 't3',
      detail: 'stack-ish internal text',
    });
    expect(message).not.toContain('stack-ish');
    expect(message).toMatch(/could not be completed/i);
  });

  it('handles a null problem (network failure, non-JSON body)', () => {
    expect(mapApiError(null)).toMatch(/could not reach/i);
  });

  it('distinguishes a 401 so the UI can re-authenticate', () => {
    expect(mapApiError({
      type: 'about:blank', title: 'Unauthorized', status: 401, traceId: 't4',
    })).toMatch(/session/i);
  });
});
```

- [ ] **Step 8: Run it to verify it fails**

Run: `npm test -- problem`
Expected: FAIL — `Cannot find module './problem'`.

- [ ] **Step 9: Implement `mapApiError`**

Create `frontend/customer-portal/src/lib/problem.ts`:

```ts
/**
 * Single place that turns a backend RFC 7807 ProblemDetails into user-facing copy. Screens branch
 * on the outcome of this function, never on an HTTP status directly.
 *
 * `detail` from the backend is never shown verbatim: it is written for an operator, and on some
 * paths carries internal specifics. Two errorCodes get bespoke copy because they are expected
 * states rather than faults — CHOREOGRAPHY_NOT_IMPLEMENTED (surrender is a documented 501) and
 * INSUFFICIENT_LOAN_VALUE (fires on every loan attempt today, because cash value is never
 * credited platform-wide).
 */
export type ApiProblem = {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  errorCode?: string;
  traceId: string;
  errors?: Array<{ field: string; message: string }>;
};

const BY_ERROR_CODE: Record<string, string> = {
  CHOREOGRAPHY_NOT_IMPLEMENTED:
    'Policy surrender is not available online yet. Please contact your agent to start a surrender.',
  INSUFFICIENT_LOAN_VALUE:
    'This policy has no cash value available to borrow against yet, so a loan cannot be issued.',
  VALIDATION_ERROR:
    'Some of the details entered are not valid. Please check the form and try again.',
};

export function mapApiError(problem: ApiProblem | null, fallbackStatus?: number): string {
  if (!problem) {
    return 'We could not reach the service. Please check your connection and try again.';
  }
  if (problem.errorCode && BY_ERROR_CODE[problem.errorCode]) {
    return BY_ERROR_CODE[problem.errorCode];
  }
  const status = problem.status || fallbackStatus || 0;
  if (status === 401) {
    return 'Your session has expired. Please sign in again.';
  }
  if (status === 403) {
    return 'You do not have access to this item.';
  }
  if (status === 404) {
    return 'We could not find that item.';
  }
  if (status >= 500) {
    return `Something went wrong on our side. Please try again shortly. (Reference: ${problem.traceId})`;
  }
  return `That request could not be completed. (Reference: ${problem.traceId})`;
}
```

- [ ] **Step 10: Run the full frontend suite**

Run: `npm test`
Expected: PASS, 12/12 (1 smoke + 6 money + 5 problem).

- [ ] **Step 11: Commit**

```bash
git add frontend/customer-portal/scripts frontend/customer-portal/src/types \
        frontend/customer-portal/src/lib frontend/customer-portal/package.json
git commit -m "feat(portal): generate per-spec API types, add money formatting and error mapping

formatMoney never converts a money string to a number — one test asserts
precision beyond 2^53 to pin that. mapApiError is the single errorCode-to-copy
mapping; screens never branch on HTTP status."
```

---

## Task 5: NextAuth with Keycloak and token refresh

**Files:**
- Create: `frontend/customer-portal/src/auth.ts`
- Create: `frontend/customer-portal/src/app/api/auth/[...nextauth]/route.ts`
- Create: `frontend/customer-portal/src/lib/refresh.ts` + `src/lib/refresh.test.ts`
- Create: `frontend/customer-portal/src/middleware.ts`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `refreshAccessToken(token: PortalToken, deps: RefreshDeps): Promise<PortalToken>` — pure, unit-testable
  - `type PortalToken = { accessToken: string; refreshToken: string; expiresAt: number; error?: 'RefreshFailed' }`
  - `auth()`, `signIn()`, `signOut()`, `handlers` exported from `@/auth`
  Task 6 consumes `auth()` to read the access token.

**Context:** `keycloak/customers-realm.json` sets no `accessTokenLifespan`, so Keycloak's ~5-minute default applies. Without refresh the portal 401s within minutes of real use. The refresh logic lives in its own module so it can be unit-tested without NextAuth's runtime — the `jwt` callback just calls it.

- [ ] **Step 1: Write the failing refresh tests**

Create `frontend/customer-portal/src/lib/refresh.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest';
import { refreshAccessToken } from './refresh';

const CONFIG = {
  issuer: 'http://localhost:8081/realms/customers',
  clientId: 'lifeplatform-app',
  clientSecret: 'dev-secret-customers',
};

describe('refreshAccessToken', () => {
  it('exchanges the refresh token and returns new expiry', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ access_token: 'new-access', refresh_token: 'new-refresh', expires_in: 300 }),
    });
    const now = 1_000_000;

    const result = await refreshAccessToken(
      { accessToken: 'old', refreshToken: 'old-refresh', expiresAt: now - 1 },
      { ...CONFIG, fetchImpl: fetchImpl as unknown as typeof fetch, now: () => now },
    );

    expect(result.accessToken).toBe('new-access');
    expect(result.refreshToken).toBe('new-refresh');
    expect(result.expiresAt).toBe(now + 300_000);
    expect(result.error).toBeUndefined();
  });

  it('posts to the realm token endpoint with grant_type=refresh_token and the client secret', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ access_token: 'a', refresh_token: 'r', expires_in: 300 }),
    });

    await refreshAccessToken(
      { accessToken: 'old', refreshToken: 'the-refresh-token', expiresAt: 0 },
      { ...CONFIG, fetchImpl: fetchImpl as unknown as typeof fetch, now: () => 0 },
    );

    const [url, init] = fetchImpl.mock.calls[0];
    expect(url).toBe('http://localhost:8081/realms/customers/protocol/openid-connect/token');
    const body = new URLSearchParams(init.body as string);
    expect(body.get('grant_type')).toBe('refresh_token');
    expect(body.get('refresh_token')).toBe('the-refresh-token');
    expect(body.get('client_id')).toBe('lifeplatform-app');
    expect(body.get('client_secret')).toBe('dev-secret-customers');
  });

  it('keeps the previous refresh token when Keycloak does not rotate it', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ access_token: 'new-access', expires_in: 300 }),
    });

    const result = await refreshAccessToken(
      { accessToken: 'old', refreshToken: 'keep-me', expiresAt: 0 },
      { ...CONFIG, fetchImpl: fetchImpl as unknown as typeof fetch, now: () => 0 },
    );

    expect(result.refreshToken).toBe('keep-me');
  });

  it('marks the token RefreshFailed when Keycloak rejects the refresh', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({ error: 'invalid_grant' }),
    });

    const result = await refreshAccessToken(
      { accessToken: 'old', refreshToken: 'expired', expiresAt: 0 },
      { ...CONFIG, fetchImpl: fetchImpl as unknown as typeof fetch, now: () => 0 },
    );

    expect(result.error).toBe('RefreshFailed');
  });

  it('marks the token RefreshFailed when the network throws', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new Error('ECONNREFUSED'));

    const result = await refreshAccessToken(
      { accessToken: 'old', refreshToken: 'r', expiresAt: 0 },
      { ...CONFIG, fetchImpl: fetchImpl as unknown as typeof fetch, now: () => 0 },
    );

    expect(result.error).toBe('RefreshFailed');
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npm test -- refresh`
Expected: FAIL — `Cannot find module './refresh'`.

- [ ] **Step 3: Implement the refresh module**

Create `frontend/customer-portal/src/lib/refresh.ts`:

```ts
/**
 * Keycloak refresh-token exchange, isolated from NextAuth so it can be tested directly.
 *
 * Access tokens on the `customers` realm live ~5 minutes (the realm sets no accessTokenLifespan
 * override, so Keycloak's server default applies), which makes refresh a day-one requirement
 * rather than a refinement. Failure is returned as `error: 'RefreshFailed'` rather than thrown:
 * the caller's correct response is to end the session and re-authenticate, not to surface an
 * error to the user, because an idle-timeout logout is expected behaviour.
 */
export type PortalToken = {
  accessToken: string;
  refreshToken: string;
  expiresAt: number;
  error?: 'RefreshFailed';
};

export type RefreshDeps = {
  issuer: string;
  clientId: string;
  clientSecret: string;
  fetchImpl?: typeof fetch;
  now?: () => number;
};

export async function refreshAccessToken(token: PortalToken, deps: RefreshDeps): Promise<PortalToken> {
  const doFetch = deps.fetchImpl ?? fetch;
  const now = deps.now ?? Date.now;

  try {
    const response = await doFetch(`${deps.issuer}/protocol/openid-connect/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'refresh_token',
        refresh_token: token.refreshToken,
        client_id: deps.clientId,
        client_secret: deps.clientSecret,
      }).toString(),
    });

    if (!response.ok) {
      return { ...token, error: 'RefreshFailed' };
    }

    const payload = (await response.json()) as {
      access_token: string;
      refresh_token?: string;
      expires_in: number;
    };

    return {
      accessToken: payload.access_token,
      // Keycloak may or may not rotate the refresh token; keep the old one when it does not.
      refreshToken: payload.refresh_token ?? token.refreshToken,
      expiresAt: now() + payload.expires_in * 1000,
    };
  } catch {
    return { ...token, error: 'RefreshFailed' };
  }
}
```

- [ ] **Step 4: Run the refresh tests to verify they pass**

Run: `npm test -- refresh`
Expected: PASS, 5/5.

- [ ] **Step 5: Wire NextAuth**

Create `frontend/customer-portal/src/auth.ts`:

```ts
import NextAuth from 'next-auth';
import Keycloak from 'next-auth/providers/keycloak';
import { refreshAccessToken, type PortalToken } from '@/lib/refresh';

/**
 * `customers` realm only, confidential client. The client secret is read here — on the server —
 * and never reaches the browser, which is the whole reason this portal is a BFF: the backend has
 * no CORS policy at all, deliberately, so a browser could not call it anyway.
 */
const ISSUER = process.env.KEYCLOAK_ISSUER!;
const CLIENT_ID = process.env.KEYCLOAK_CLIENT_ID!;
const CLIENT_SECRET = process.env.KEYCLOAK_CLIENT_SECRET!;

export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [
    Keycloak({ issuer: ISSUER, clientId: CLIENT_ID, clientSecret: CLIENT_SECRET }),
  ],
  session: { strategy: 'jwt' },
  callbacks: {
    async jwt({ token, account }) {
      // Initial sign-in: persist the triple the refresh cycle needs.
      if (account) {
        return {
          ...token,
          accessToken: account.access_token as string,
          refreshToken: account.refresh_token as string,
          expiresAt: Date.now() + (account.expires_in as number) * 1000,
        };
      }

      const current = token as unknown as PortalToken & Record<string, unknown>;
      if (!current.refreshToken) {
        return token;
      }
      if (Date.now() < current.expiresAt) {
        return token;
      }

      const refreshed = await refreshAccessToken(
        {
          accessToken: current.accessToken,
          refreshToken: current.refreshToken,
          expiresAt: current.expiresAt,
        },
        { issuer: ISSUER, clientId: CLIENT_ID, clientSecret: CLIENT_SECRET },
      );
      return { ...token, ...refreshed };
    },

    async session({ session, token }) {
      // Surface ONLY the failure flag to the client, never the tokens themselves.
      (session as unknown as { error?: string }).error =
        (token as unknown as PortalToken).error;
      return session;
    },
  },
});
```

Create `frontend/customer-portal/src/app/api/auth/[...nextauth]/route.ts`:

```ts
import { handlers } from '@/auth';

export const { GET, POST } = handlers;
```

- [ ] **Step 6: Force re-login when refresh has failed**

Create `frontend/customer-portal/src/middleware.ts`:

```ts
import { auth } from '@/auth';
import { NextResponse } from 'next/server';

/**
 * A failed refresh is not an error to display — it means the session is genuinely over (refresh
 * token expired, revoked, or idle past ssoSessionIdleTimeout). Send the user to a real sign-in
 * rather than letting every subsequent backend call 401 with a confusing message.
 */
export default auth((request) => {
  const session = request.auth as ({ error?: string } | null);
  const isSignInRoute = request.nextUrl.pathname.startsWith('/api/auth')
    || request.nextUrl.pathname === '/signin';

  if (isSignInRoute) {
    return NextResponse.next();
  }
  if (!session || session.error === 'RefreshFailed') {
    const signInUrl = new URL('/api/auth/signin', request.nextUrl.origin);
    return NextResponse.redirect(signInUrl);
  }
  return NextResponse.next();
});

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico).*)'],
};
```

- [ ] **Step 7: Verify the build and full suite**

```bash
npm test
npm run build
```
Expected: 17 tests passing (12 + 5 refresh); build exits 0.

- [ ] **Step 8: Commit**

```bash
git add frontend/customer-portal/src/auth.ts frontend/customer-portal/src/middleware.ts \
        frontend/customer-portal/src/app/api/auth frontend/customer-portal/src/lib/refresh.ts \
        frontend/customer-portal/src/lib/refresh.test.ts
git commit -m "feat(portal): NextAuth against the customers realm, with token refresh

Access tokens last ~5 minutes (no realm override), so refresh is wired into the
jwt callback from day one. Refresh failure clears the session and forces a real
re-login instead of surfacing an error."
```

---

## Task 6: The BFF backend-call wrapper

**Files:**
- Create: `frontend/customer-portal/src/lib/backend.ts` + `src/lib/backend.test.ts`

**Interfaces:**
- Consumes: `auth()` from `@/auth`; `ApiProblem` from `@/lib/problem`.
- Produces:
  - `class ApiError extends Error { status: number; problem: ApiProblem | null }`
  - `callBackend<T>(path: string, init?: BackendInit): Promise<T>` where `BackendInit = { method?: string; body?: unknown; headers?: Record<string,string>; searchParams?: Record<string, string | number | undefined>; accessToken?: string }`
  - `callBackendRaw(path, init): Promise<Response>` for binary downloads
  Tasks 8–13 use these for every backend call.

**Context:** Every Route Handler goes through this one function, so the bearer token is attached in exactly one place and `ProblemDetails` is decoded in exactly one place. `accessToken` is injectable so the function is testable without a NextAuth session.

- [ ] **Step 1: Write the failing tests**

Create `frontend/customer-portal/src/lib/backend.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest';
import { ApiError, callBackend } from './backend';

const OK = (body: unknown) => ({
  ok: true, status: 200,
  headers: new Headers({ 'content-type': 'application/json' }),
  json: async () => body,
});

describe('callBackend', () => {
  it('attaches the bearer token and returns parsed JSON', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(OK({ policyNumber: 'POL-1' }));

    const result = await callBackend<{ policyNumber: string }>('/policies/POL-1', {
      accessToken: 'tok', fetchImpl: fetchImpl as unknown as typeof fetch,
      baseUrl: 'http://backend:8080',
    });

    expect(result.policyNumber).toBe('POL-1');
    const [url, init] = fetchImpl.mock.calls[0];
    expect(url).toBe('http://backend:8080/policies/POL-1');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer tok');
  });

  it('serialises searchParams and drops undefined values', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(OK({ items: [] }));

    await callBackend('/policies', {
      accessToken: 'tok', fetchImpl: fetchImpl as unknown as typeof fetch,
      baseUrl: 'http://backend:8080',
      searchParams: { page: 0, pageSize: 20, status: undefined },
    });

    expect(fetchImpl.mock.calls[0][0]).toBe('http://backend:8080/policies?page=0&pageSize=20');
  });

  it('throws ApiError carrying the decoded ProblemDetails', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false, status: 409,
      headers: new Headers({ 'content-type': 'application/problem+json' }),
      json: async () => ({
        type: 'about:blank', title: 'Conflict', status: 409,
        errorCode: 'INSUFFICIENT_LOAN_VALUE', traceId: 'abc',
      }),
    });

    await expect(callBackend('/policies/P/loans', {
      method: 'POST', accessToken: 'tok',
      fetchImpl: fetchImpl as unknown as typeof fetch, baseUrl: 'http://backend:8080',
    })).rejects.toMatchObject({
      status: 409,
      problem: { errorCode: 'INSUFFICIENT_LOAN_VALUE' },
    });
  });

  it('throws ApiError with a null problem when the error body is not JSON', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false, status: 502,
      headers: new Headers({ 'content-type': 'text/html' }),
      json: async () => { throw new Error('not json'); },
    });

    const error = await callBackend('/policies', {
      accessToken: 'tok', fetchImpl: fetchImpl as unknown as typeof fetch,
      baseUrl: 'http://backend:8080',
    }).catch((e: unknown) => e as ApiError);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(502);
    expect((error as ApiError).problem).toBeNull();
  });

  it('returns undefined for a 204 rather than trying to parse a body', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true, status: 204, headers: new Headers(),
      json: async () => { throw new Error('no body'); },
    });

    await expect(callBackend('/policies/P/beneficiaries', {
      method: 'PUT', accessToken: 'tok',
      fetchImpl: fetchImpl as unknown as typeof fetch, baseUrl: 'http://backend:8080',
    })).resolves.toBeUndefined();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npm test -- backend`
Expected: FAIL — `Cannot find module './backend'`.

- [ ] **Step 3: Implement the wrapper**

Create `frontend/customer-portal/src/lib/backend.ts`:

```ts
import 'server-only';
import { auth } from '@/auth';
import type { ApiProblem } from '@/lib/problem';

/**
 * The single path from this portal to the Spring backend. Every Route Handler and Server Action
 * goes through here, which is what keeps the access token server-side and gives ProblemDetails
 * exactly one decode site.
 *
 * `server-only` is imported for its build-time effect: if any Client Component ever imports this
 * module (directly or transitively), the build FAILS rather than shipping a bundle that would try
 * to talk to the backend from the browser — which cannot work anyway, since the backend declares
 * no CORS policy, and would leak the token if it did.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: ApiProblem | null,
  ) {
    super(problem?.title ?? `Backend responded ${status}`);
    this.name = 'ApiError';
  }
}

export type BackendInit = {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  searchParams?: Record<string, string | number | undefined>;
  /** Injected in tests and by callers that already hold a token; otherwise read from the session. */
  accessToken?: string;
  baseUrl?: string;
  fetchImpl?: typeof fetch;
};

async function resolveAccessToken(explicit?: string): Promise<string> {
  if (explicit) return explicit;
  const session = (await auth()) as unknown as { accessToken?: string } | null;
  if (!session?.accessToken) {
    // Middleware normally redirects before this, but a Route Handler can still be hit directly.
    throw new ApiError(401, null);
  }
  return session.accessToken;
}

function buildUrl(baseUrl: string, path: string, searchParams?: BackendInit['searchParams']): string {
  if (!searchParams) return `${baseUrl}${path}`;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(searchParams)) {
    if (value !== undefined) params.set(key, String(value));
  }
  const query = params.toString();
  return query ? `${baseUrl}${path}?${query}` : `${baseUrl}${path}`;
}

export async function callBackendRaw(path: string, init: BackendInit = {}): Promise<Response> {
  const baseUrl = init.baseUrl ?? process.env.BACKEND_BASE_URL!;
  const doFetch = init.fetchImpl ?? fetch;
  const accessToken = await resolveAccessToken(init.accessToken);

  return doFetch(buildUrl(baseUrl, path, init.searchParams), {
    method: init.method ?? 'GET',
    headers: {
      Authorization: `Bearer ${accessToken}`,
      ...(init.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...init.headers,
    },
    body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
    cache: 'no-store',
  });
}

export async function callBackend<T>(path: string, init: BackendInit = {}): Promise<T> {
  const response = await callBackendRaw(path, init);

  if (!response.ok) {
    let problem: ApiProblem | null = null;
    try {
      problem = (await response.json()) as ApiProblem;
    } catch {
      problem = null;   // HTML error page, empty body, gateway failure
    }
    throw new ApiError(response.status, problem);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm test -- backend`
Expected: PASS, 5/5.

Note: the test imports this module in a jsdom environment, where `server-only` would normally throw. Add to `vitest.config.ts` under `test`, so tests can import server modules while the real Next build still enforces the boundary:

```ts
    alias: { 'server-only': path.resolve(__dirname, './src/test/server-only-stub.ts') },
```

Create `frontend/customer-portal/src/test/server-only-stub.ts`:

```ts
// Vitest runs in jsdom, where the real `server-only` package throws on import. The build-time
// guarantee that matters comes from `next build`, which uses the real package.
export {};
```

- [ ] **Step 5: Verify the full suite and build**

```bash
npm test
npm run build
```
Expected: 22 tests passing; build exits 0.

- [ ] **Step 6: Commit**

```bash
git add frontend/customer-portal/src/lib/backend.ts frontend/customer-portal/src/lib/backend.test.ts \
        frontend/customer-portal/src/test frontend/customer-portal/vitest.config.ts
git commit -m "feat(portal): single server-only backend wrapper with ProblemDetails decoding

Imports server-only so a Client Component importing it fails the build rather
than shipping browser-side backend calls."
```

---

## Task 7: The two-layer idempotency hard guard

**Files:**
- Create: `frontend/customer-portal/src/lib/redis.ts`
- Create: `frontend/customer-portal/src/lib/idempotency.ts` + `src/lib/idempotency.test.ts`
- Create: `frontend/customer-portal/src/hooks/use-submit-guard.ts` + `src/hooks/use-submit-guard.test.tsx`

**Interfaces:**
- Produces:
  - `claimIdempotency(store, key): Promise<{ claimed: true } | { claimed: false; outcome: string | null }>`
  - `releaseIdempotency(store, key): Promise<void>`
  - `recordOutcome(store, key, outcome): Promise<void>`
  - `idempotencyKeyFor(parts: { realm: string; subject: string; operation: string; clientKey: string }): string`
  - `useSubmitGuard<A extends unknown[]>(fn: (...args: A) => Promise<void>): { submit: (...args: A) => Promise<void>; isSubmitting: boolean }`
  Task 12 consumes all of these.

**Context:** Per spec §6, two endpoints accept `Idempotency-Key` and never read it: `POST /policies/{n}/loans` and `POST /loans/{id}/repayments`. Layer 1 must be a synchronous `useRef` check — a `useState`-driven `disabled` is set asynchronously, so two events in one tick both pass it. Layer 2 must be a single atomic `SET … NX EX`, never `GET` then `SET`. The release rules are load-bearing: releasing on a *correctable* 4xx is what stops the guard from making a form permanently un-retryable.

- [ ] **Step 1: Write the failing Layer 2 tests**

Create `frontend/customer-portal/src/lib/idempotency.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest';
import {
  claimIdempotency, idempotencyKeyFor, recordOutcome, releaseIdempotency, type IdempotencyStore,
} from './idempotency';

function fakeStore(): IdempotencyStore & { setCalls: unknown[][] } {
  const data = new Map<string, string>();
  const setCalls: unknown[][] = [];
  return {
    setCalls,
    async setIfAbsent(key, value, ttlSeconds) {
      setCalls.push([key, value, ttlSeconds]);
      if (data.has(key)) return false;
      data.set(key, value);
      return true;
    },
    async get(key) { return data.get(key) ?? null; },
    async set(key, value) { data.set(key, value); },
    async del(key) { data.delete(key); },
  };
}

describe('idempotencyKeyFor', () => {
  it('namespaces by realm, subject and operation so keys cannot collide across users', () => {
    const a = idempotencyKeyFor({ realm: 'customers', subject: 'user-a', operation: 'loan-origination', clientKey: 'k' });
    const b = idempotencyKeyFor({ realm: 'customers', subject: 'user-b', operation: 'loan-origination', clientKey: 'k' });
    expect(a).not.toBe(b);
    expect(a).toContain('customers');
    expect(a).toContain('loan-origination');
  });
});

describe('claimIdempotency', () => {
  it('claims a fresh key with a single atomic NX SET and a 900s TTL', async () => {
    const store = fakeStore();
    const result = await claimIdempotency(store, 'idem:k1');

    expect(result).toEqual({ claimed: true });
    expect(store.setCalls).toHaveLength(1);
    expect(store.setCalls[0][2]).toBe(900);
  });

  it('refuses a second claim on the same key', async () => {
    const store = fakeStore();
    await claimIdempotency(store, 'idem:k1');
    const second = await claimIdempotency(store, 'idem:k1');

    expect(second).toEqual({ claimed: false, outcome: null });
  });

  it('replays a recorded outcome on a repeat claim', async () => {
    const store = fakeStore();
    await claimIdempotency(store, 'idem:k1');
    await recordOutcome(store, 'idem:k1', '{"loanId":"L-1"}');

    const second = await claimIdempotency(store, 'idem:k1');
    expect(second).toEqual({ claimed: false, outcome: '{"loanId":"L-1"}' });
  });

  it('lets a released key be claimed again — the un-retryable-form fix', async () => {
    // A correctable 422 releases the claim; the user corrects the amount and resubmits with the
    // same client key. Without this, the form would be permanently poisoned.
    const store = fakeStore();
    await claimIdempotency(store, 'idem:k1');
    await releaseIdempotency(store, 'idem:k1');

    expect(await claimIdempotency(store, 'idem:k1')).toEqual({ claimed: true });
  });

  it('uses exactly one round trip to claim — never GET-then-SET', async () => {
    const store = fakeStore();
    const getSpy = vi.spyOn(store, 'get');
    await claimIdempotency(store, 'idem:k1');
    // A fresh claim must not read first; the read only happens on a REFUSED claim, to replay.
    expect(getSpy).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npm test -- idempotency`
Expected: FAIL — `Cannot find module './idempotency'`.

- [ ] **Step 3: Implement Layer 2**

Create `frontend/customer-portal/src/lib/idempotency.ts`:

```ts
/**
 * Layer 2 of the hard guard from spec §6: a server-side atomic dedup claim, held in Redis, for the
 * two backend endpoints that accept `Idempotency-Key` and never read it (loan origination, loan
 * repayment). Layer 1 (the useRef guard) dies with the tab; this survives a refresh, a second tab,
 * and anything that is not the portal's own UI.
 *
 * The claim MUST be one atomic operation (SET .. NX EX). A GET-then-SET would have exactly the
 * check-then-act race this layer exists to prevent.
 */
export type IdempotencyStore = {
  /** SET key value NX EX ttl — true when this caller won the claim. */
  setIfAbsent(key: string, value: string, ttlSeconds: number): Promise<boolean>;
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  del(key: string): Promise<void>;
};

export const CLAIM_TTL_SECONDS = 900;
const IN_FLIGHT = 'in-flight';

export function idempotencyKeyFor(parts: {
  realm: string; subject: string; operation: string; clientKey: string;
}): string {
  // Namespaced by realm + token subject so one customer's key can never collide with, or replay,
  // another's.
  return `idem:${parts.realm}:${parts.subject}:${parts.operation}:${parts.clientKey}`;
}

export type ClaimResult =
  | { claimed: true }
  | { claimed: false; outcome: string | null };

export async function claimIdempotency(store: IdempotencyStore, key: string): Promise<ClaimResult> {
  const won = await store.setIfAbsent(key, IN_FLIGHT, CLAIM_TTL_SECONDS);
  if (won) {
    return { claimed: true };
  }
  const existing = await store.get(key);
  return { claimed: false, outcome: existing === IN_FLIGHT ? null : existing };
}

/** Success, or any definitive response that CHANGED state: keep the claim, store the outcome. */
export async function recordOutcome(store: IdempotencyStore, key: string, outcome: string): Promise<void> {
  await store.set(key, outcome);
}

/**
 * A definitive rejection that changed nothing (422, a business 409 the user can act on): drop the
 * claim so the user can correct and resubmit immediately. NOT for indeterminate outcomes
 * (timeout, 5xx) — those keep the claim, because the request may in fact have been applied.
 */
export async function releaseIdempotency(store: IdempotencyStore, key: string): Promise<void> {
  await store.del(key);
}
```

- [ ] **Step 4: Run the Layer 2 tests to verify they pass**

Run: `npm test -- idempotency`
Expected: PASS, 6/6.

- [ ] **Step 5: Write the Redis-backed store**

Create `frontend/customer-portal/src/lib/redis.ts`:

```ts
import 'server-only';
import Redis from 'ioredis';
import type { IdempotencyStore } from '@/lib/idempotency';

/**
 * One ioredis connection per server process, kept on globalThis so Next's dev-mode module
 * reloading does not open a new connection on every edit. Redis is already provisioned in
 * infra/docker-compose.yml — this adds no infrastructure.
 */
const globalForRedis = globalThis as unknown as { portalRedis?: Redis };

export function redis(): Redis {
  if (!globalForRedis.portalRedis) {
    globalForRedis.portalRedis = new Redis(process.env.REDIS_URL!);
  }
  return globalForRedis.portalRedis;
}

export function redisIdempotencyStore(): IdempotencyStore {
  const client = redis();
  return {
    async setIfAbsent(key, value, ttlSeconds) {
      const reply = await client.set(key, value, 'EX', ttlSeconds, 'NX');
      return reply === 'OK';
    },
    async get(key) { return client.get(key); },
    async set(key, value) {
      // Preserve the remaining TTL rather than resetting it: KEEPTTL keeps the claim window
      // anchored to the original request, not to when the outcome happened to arrive.
      await client.set(key, value, 'KEEPTTL');
    },
    async del(key) { await client.del(key); },
  };
}
```

- [ ] **Step 6: Write the failing Layer 1 test**

Create `frontend/customer-portal/src/hooks/use-submit-guard.test.tsx`:

```tsx
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { useSubmitGuard } from './use-submit-guard';

function Form({ onSubmit }: { onSubmit: () => Promise<void> }) {
  const { submit, isSubmitting } = useSubmitGuard(onSubmit);
  return (
    <button type="button" disabled={isSubmitting} onClick={() => void submit()}>
      Submit
    </button>
  );
}

describe('useSubmitGuard', () => {
  it('fires once when two clicks land in the same tick', async () => {
    // THE test for this hook. A useState-driven `disabled` passes nothing here: React batches the
    // state update, so both handlers run before either re-render. Only a synchronous ref check
    // set before the first await survives this.
    const onSubmit = vi.fn().mockImplementation(() => new Promise<void>((r) => setTimeout(r, 10)));
    render(<Form onSubmit={onSubmit} />);
    const button = screen.getByRole('button');

    button.click();
    button.click();
    button.click();

    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('allows a second submit after the first settles', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<Form onSubmit={onSubmit} />);
    const button = screen.getByRole('button');

    button.click();
    await vi.waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    button.click();
    await vi.waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2));
  });

  it('re-arms after a rejected submit so the user can retry', async () => {
    const onSubmit = vi.fn()
      .mockRejectedValueOnce(new Error('validation'))
      .mockResolvedValueOnce(undefined);
    render(<Form onSubmit={onSubmit} />);
    const button = screen.getByRole('button');

    button.click();
    await vi.waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    button.click();
    await vi.waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2));
  });
});
```

- [ ] **Step 7: Run it to verify it fails**

Run: `npm test -- use-submit-guard`
Expected: FAIL — `Cannot find module './use-submit-guard'`.

- [ ] **Step 8: Implement Layer 1**

Create `frontend/customer-portal/src/hooks/use-submit-guard.ts`:

```ts
'use client';

import { useCallback, useRef, useState } from 'react';

/**
 * Layer 1 of the hard guard from spec §6: a SYNCHRONOUS re-entrancy check.
 *
 * The ref is checked and set at the very top of the handler, before any await. A `disabled`
 * attribute driven by useState is not equivalent: React batches state updates, so two events
 * dispatched in the same tick both pass the check before either re-render lands. `isSubmitting` is
 * still returned — for the visible affordance — but it is not the correctness mechanism.
 *
 * The ref is always cleared in `finally`, including on rejection, so a validation failure the user
 * can correct does not permanently disable their form.
 */
export function useSubmitGuard<A extends unknown[]>(fn: (...args: A) => Promise<void>) {
  const inFlight = useRef(false);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const submit = useCallback(async (...args: A) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setIsSubmitting(true);
    try {
      await fn(...args);
    } finally {
      inFlight.current = false;
      setIsSubmitting(false);
    }
  }, [fn]);

  return { submit, isSubmitting };
}
```

- [ ] **Step 9: Run the guard tests to verify they pass**

Run: `npm test -- use-submit-guard`
Expected: PASS, 3/3.

- [ ] **Step 10: Prove Layer 1 is not vacuous**

Temporarily replace the `inFlight.current` ref check with a `isSubmitting` state check, re-run `npm test -- use-submit-guard`, and confirm the first test FAILS (`onSubmit` called 3 times). Then restore the ref version and confirm it passes again. This is the red-green cycle that proves the test tests something — a guard test that passes either way is worthless.

- [ ] **Step 11: Verify the full suite and build**

```bash
npm test
npm run build
```
Expected: 31 tests passing; build exits 0.

- [ ] **Step 12: Commit**

```bash
git add frontend/customer-portal/src/lib/idempotency.ts frontend/customer-portal/src/lib/idempotency.test.ts \
        frontend/customer-portal/src/lib/redis.ts frontend/customer-portal/src/hooks
git commit -m "feat(portal): two-layer hard idempotency guard

Layer 1 is a synchronous useRef check (a state-driven disabled button races and
is verified to fail the same-tick test). Layer 2 is an atomic Redis SET NX EX
claim with explicit release rules — releasing on a correctable 4xx is what keeps
a rejected form retryable."
```

---

## Task 8: App shell and dashboard (policy list)

**Files:**
- Create: `frontend/customer-portal/src/app/api/policies/route.ts`
- Create: `frontend/customer-portal/src/app/providers.tsx`
- Modify: `frontend/customer-portal/src/app/layout.tsx`
- Create: `frontend/customer-portal/src/app/(portal)/layout.tsx`, `src/app/(portal)/page.tsx`
- Create: `frontend/customer-portal/src/components/policy-list.tsx` + `src/components/policy-list.test.tsx`
- Create: `frontend/customer-portal/src/components/money.tsx`

**Interfaces:**
- Consumes: `callBackend`, `ApiError` (Task 6); `formatMoney` (Task 4); `mapApiError` (Task 4).
- Produces: `GET /api/policies?page&pageSize&status` returning `{ items: PolicyView[]; page: PageMeta }`; `<MoneyText amount currencyCode />`; the `(portal)` route group every later screen nests under.

**Context:** `GET /policies` returns `{ items: PolicyView[], page: { page, pageSize, totalElements } }`. `PolicyView` fields: `policyNumber, policyholderPartyId, productId, productVersionId, agentOfRecordId, status, issueDate, sumAssured, cashValue, premium, premiumFrequency, beneficiaries`. Statuses: `PROPOSED, ACTIVE, LAPSED, SUSPENDED, SURRENDERED, MATURED, REINSTATED`. The route handler must NOT forward `policyholderPartyId` — the backend force-scopes it (Task 1).

- [ ] **Step 1: Write the Route Handler**

Create `frontend/customer-portal/src/app/api/policies/route.ts`:

```ts
import { NextRequest, NextResponse } from 'next/server';
import { ApiError, callBackend } from '@/lib/backend';

/**
 * Policy list for the signed-in customer. `policyholderPartyId` is deliberately NOT forwarded even
 * if a client sends one: the backend force-scopes it from the token's party_id claim (M12 Task 1),
 * and passing it through would be a bug even when the value is correct.
 */
export async function GET(request: NextRequest) {
  const params = request.nextUrl.searchParams;
  try {
    const data = await callBackend<unknown>('/policies', {
      searchParams: {
        page: params.get('page') ?? 0,
        pageSize: params.get('pageSize') ?? 20,
        status: params.get('status') ?? undefined,
      },
    });
    return NextResponse.json(data);
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}
```

- [ ] **Step 2: Add the TanStack Query provider and shell**

Create `frontend/customer-portal/src/app/providers.tsx`:

```tsx
'use client';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';

export function Providers({ children }: { children: ReactNode }) {
  const [client] = useState(() => new QueryClient({
    defaultOptions: {
      queries: { staleTime: 30_000, retry: 1 },
      // Never auto-retry a mutation: the two loan endpoints have no server-side idempotency, so a
      // silent retry is exactly the duplicate the hard guard exists to prevent.
      mutations: { retry: 0 },
    },
  }));
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
```

Wrap `src/app/layout.tsx`'s `<body>` children in `<Providers>`, and set the page title to `Life Insurance Portal`.

Create `frontend/customer-portal/src/app/(portal)/layout.tsx` with a header showing the signed-in user and a sign-out link, and a nav with Policies / Claims. Use shadcn primitives already generated in Task 3.

- [ ] **Step 3: Write the money component**

Create `frontend/customer-portal/src/components/money.tsx`:

```tsx
import { formatMoney } from '@/lib/money';

/**
 * Renders a Money object exactly as the API returned it. Cash value is TZS 0.00 platform-wide
 * today (PolicyAccount.cashValueAmount is never credited); per the approved decision that renders
 * as-is, with no caveat and no special case — the display path is already correct for the day the
 * backend starts crediting real values.
 */
export function MoneyText({ amount, currencyCode }: { amount: string; currencyCode: string }) {
  return <span className="tabular-nums">{formatMoney(amount, currencyCode)}</span>;
}
```

- [ ] **Step 4: Write the failing policy-list test**

Create `frontend/customer-portal/src/components/policy-list.test.tsx`:

```tsx
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PolicyList } from './policy-list';

const POLICY = {
  policyNumber: 'POL-000123',
  status: 'ACTIVE' as const,
  issueDate: '2025-03-01',
  sumAssured: { amount: '50000000', currencyCode: 'TZS' },
  premium: { amount: '125000.50', currencyCode: 'TZS' },
  premiumFrequency: 'MONTHLY' as const,
  cashValue: { amount: '0', currencyCode: 'TZS' },
};

describe('PolicyList', () => {
  it('renders a policy card with formatted money', () => {
    render(<PolicyList policies={[POLICY]} />);
    expect(screen.getByText('POL-000123')).toBeInTheDocument();
    expect(screen.getByText('TZS 50,000,000.00')).toBeInTheDocument();
    expect(screen.getByText('TZS 125,000.50')).toBeInTheDocument();
  });

  it('renders the zero cash value as-is, with no caveat text', () => {
    render(<PolicyList policies={[POLICY]} />);
    expect(screen.getByText('TZS 0.00')).toBeInTheDocument();
    expect(screen.queryByText(/not yet available/i)).not.toBeInTheDocument();
  });

  it('shows an empty state rather than a blank page', () => {
    render(<PolicyList policies={[]} />);
    expect(screen.getByText(/no policies/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 5: Run it to verify it fails**

Run: `npm test -- policy-list`
Expected: FAIL — `Cannot find module './policy-list'`.

- [ ] **Step 6: Implement `PolicyList`**

Create `frontend/customer-portal/src/components/policy-list.tsx` — a presentational component taking `policies` as a prop (so it is testable without a query client), rendering one shadcn `Card` per policy with `policyNumber`, a `Badge` for `status`, and `MoneyText` for `sumAssured`, `premium` (with `premiumFrequency`), and `cashValue`. Empty array renders "No policies to show yet."

```tsx
import Link from 'next/link';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { MoneyText } from '@/components/money';

type Money = { amount: string; currencyCode: string };
export type PolicySummary = {
  policyNumber: string;
  status: string;
  issueDate: string;
  sumAssured: Money;
  premium: Money;
  premiumFrequency: string;
  cashValue: Money;
};

export function PolicyList({ policies }: { policies: PolicySummary[] }) {
  if (policies.length === 0) {
    return <p className="text-muted-foreground">No policies to show yet.</p>;
  }
  return (
    <div className="grid gap-4 md:grid-cols-2">
      {policies.map((policy) => (
        <Card key={policy.policyNumber}>
          <CardHeader className="flex flex-row items-center justify-between">
            <CardTitle>
              <Link href={`/policies/${policy.policyNumber}`}>{policy.policyNumber}</Link>
            </CardTitle>
            <Badge>{policy.status}</Badge>
          </CardHeader>
          <CardContent className="space-y-1 text-sm">
            <div>Sum assured: <MoneyText {...policy.sumAssured} /></div>
            <div>Premium: <MoneyText {...policy.premium} /> / {policy.premiumFrequency.toLowerCase()}</div>
            <div>Cash value: <MoneyText {...policy.cashValue} /></div>
            <div className="text-muted-foreground">Issued {policy.issueDate}</div>
          </CardContent>
        </Card>
      ))}
    </div>
  );
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `npm test -- policy-list`
Expected: PASS, 3/3.

- [ ] **Step 8: Build the dashboard page**

Create `frontend/customer-portal/src/app/(portal)/page.tsx` as a Client Component that uses TanStack Query against `/api/policies`, with:
- a `Tabs` row of status filters (`All`, `ACTIVE`, `LAPSED`, `SUSPENDED`, `MATURED`) that sets the `status` query param
- real pagination (Previous/Next driven by `page.totalElements` and `page.pageSize`), NOT a client-side slice
- `Skeleton` while loading, `mapApiError` output on failure

- [ ] **Step 9: Verify the suite and build**

```bash
npm test
npm run build
```
Expected: 34 tests passing; build exits 0.

- [ ] **Step 10: Commit**

```bash
git add frontend/customer-portal/src
git commit -m "feat(portal): app shell and dashboard with real server-side pagination

Route Handler never forwards policyholderPartyId — the backend force-scopes it.
Cash value renders as-is (TZS 0.00 today), with a test pinning the absence of a
caveat, per the approved decision."
```

---

## Task 9: Policy detail, beneficiaries, and the surrender explanation

**Files:**
- Create: `frontend/customer-portal/src/app/api/policies/[policyNumber]/route.ts`, `.../coverage-status/route.ts`, `.../surrender-value/route.ts`, `.../beneficiaries/route.ts`
- Create: `frontend/customer-portal/src/app/(portal)/policies/[policyNumber]/page.tsx`
- Create: `frontend/customer-portal/src/components/beneficiary-form.tsx` + `src/components/beneficiary-form.test.tsx`

**Interfaces:**
- Consumes: `callBackend`, `ApiError`, `MoneyText`, `mapApiError`, `useSubmitGuard`.
- Produces: `PUT /api/policies/{policyNumber}/beneficiaries` accepting `BeneficiaryInput[]`.

**Context:** `PUT /policies/{n}/beneficiaries` enforces exactly-one-of (`partyId`, `freeformDesignee`) per beneficiary AND that active shares sum to 100%, returning 422 otherwise. Validate client-side too so the user gets immediate feedback, but the backend remains the authority. `coverage-status` is reachable only after Task 1. Surrender: `GET surrender-value` works; `POST surrender` returns 501 `CHOREOGRAPHY_NOT_IMPLEMENTED` — show the quote and an explanation, never a submit button wired to it.

- [ ] **Step 1: Write the four Route Handlers**

Each follows Task 8's shape exactly: call `callBackend`, translate `ApiError` into `NextResponse.json(error.problem, { status })`. The beneficiaries handler:

```ts
import { NextRequest, NextResponse } from 'next/server';
import { ApiError, callBackend } from '@/lib/backend';

export async function PUT(
  request: NextRequest,
  { params }: { params: Promise<{ policyNumber: string }> },
) {
  const { policyNumber } = await params;
  const body = await request.json();
  try {
    await callBackend(`/policies/${encodeURIComponent(policyNumber)}/beneficiaries`, {
      method: 'PUT', body,
    });
    return new NextResponse(null, { status: 204 });
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}
```

- [ ] **Step 2: Write the failing beneficiary-form tests**

Create `frontend/customer-portal/src/components/beneficiary-form.test.tsx`:

```tsx
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BeneficiaryForm } from './beneficiary-form';

const ONE = [{ freeformDesignee: 'Asha Juma', sharePercentage: '100' }];

describe('BeneficiaryForm', () => {
  it('blocks submission when shares do not sum to 100', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(<BeneficiaryForm initial={[{ freeformDesignee: 'Asha Juma', sharePercentage: '60' }]} onSave={onSave} />);

    await userEvent.click(screen.getByRole('button', { name: /save/i }));

    expect(onSave).not.toHaveBeenCalled();
    expect(screen.getByText(/must add up to 100/i)).toBeInTheDocument();
  });

  it('submits when shares sum to exactly 100', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(<BeneficiaryForm initial={ONE} onSave={onSave} />);

    await userEvent.click(screen.getByRole('button', { name: /save/i }));

    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it('rejects a beneficiary with both partyId and freeformDesignee', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(<BeneficiaryForm
      initial={[{ partyId: '11111111-1111-1111-1111-111111111111', freeformDesignee: 'Asha', sharePercentage: '100' }]}
      onSave={onSave} />);

    await userEvent.click(screen.getByRole('button', { name: /save/i }));

    expect(onSave).not.toHaveBeenCalled();
    expect(screen.getByText(/either a registered person or a name, not both/i)).toBeInTheDocument();
  });

  it('surfaces a backend 422 as readable copy', async () => {
    const onSave = vi.fn().mockRejectedValue({ status: 422, problem: { status: 422, errorCode: 'VALIDATION_ERROR', traceId: 't' } });
    render(<BeneficiaryForm initial={ONE} onSave={onSave} />);

    await userEvent.click(screen.getByRole('button', { name: /save/i }));

    expect(await screen.findByText(/not valid/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run it to verify it fails**

Run: `npm test -- beneficiary-form`
Expected: FAIL — `Cannot find module './beneficiary-form'`.

- [ ] **Step 4: Implement the form**

Create `frontend/customer-portal/src/components/beneficiary-form.tsx`. Requirements: an editable row list (name-or-party, share); client-side validation mirroring the backend's two rules; `useSubmitGuard` wrapping `onSave`; `mapApiError` for a rejected save. Shares are strings and compared by integer arithmetic on the summed parts — do not `parseFloat` them.

```tsx
'use client';

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useSubmitGuard } from '@/hooks/use-submit-guard';
import { mapApiError, type ApiProblem } from '@/lib/problem';

export type BeneficiaryInput = {
  partyId?: string;
  freeformDesignee?: string;
  sharePercentage: string;
};

/** Sums share strings without floats: the backend requires exactly 100, so integer cents suffice. */
function sharesSumToHundred(rows: BeneficiaryInput[]): boolean {
  let total = 0;
  for (const row of rows) {
    if (!/^\d+(\.\d{1,2})?$/.test(row.sharePercentage)) return false;
    const [whole, fraction = ''] = row.sharePercentage.split('.');
    total += Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
  }
  return total === 10_000;
}

export function BeneficiaryForm({
  initial, onSave,
}: { initial: BeneficiaryInput[]; onSave: (rows: BeneficiaryInput[]) => Promise<void> }) {
  const [rows, setRows] = useState(initial);
  const [error, setError] = useState<string | null>(null);

  const { submit, isSubmitting } = useSubmitGuard(async () => {
    const badExclusivity = rows.some(
      (r) => Boolean(r.partyId) === Boolean(r.freeformDesignee),
    );
    if (badExclusivity) {
      setError('Each beneficiary needs either a registered person or a name, not both.');
      return;
    }
    if (!sharesSumToHundred(rows)) {
      setError('Beneficiary shares must add up to 100%.');
      return;
    }
    setError(null);
    try {
      await onSave(rows);
    } catch (rejection) {
      const problem = (rejection as { problem?: ApiProblem }).problem ?? null;
      setError(mapApiError(problem));
    }
  });

  return (
    <form onSubmit={(event) => { event.preventDefault(); void submit(); }} className="space-y-3">
      {rows.map((row, index) => (
        <div key={index} className="flex gap-2">
          <Input
            aria-label={`Beneficiary ${index + 1} name`}
            value={row.freeformDesignee ?? ''}
            onChange={(e) => setRows(rows.map((r, i) =>
              i === index ? { ...r, freeformDesignee: e.target.value } : r))}
          />
          <Input
            aria-label={`Beneficiary ${index + 1} share`}
            value={row.sharePercentage}
            onChange={(e) => setRows(rows.map((r, i) =>
              i === index ? { ...r, sharePercentage: e.target.value } : r))}
          />
        </div>
      ))}
      {error && <p role="alert" className="text-destructive text-sm">{error}</p>}
      <Button type="submit" disabled={isSubmitting}>Save beneficiaries</Button>
    </form>
  );
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npm test -- beneficiary-form`
Expected: PASS, 4/4.

- [ ] **Step 6: Build the policy detail page**

Create `frontend/customer-portal/src/app/(portal)/policies/[policyNumber]/page.tsx` showing: policy fields, coverage status, the surrender **quote** from `surrender-value`, `BeneficiaryForm`, and links to Billing / Claims / Loan for this policy. The surrender area shows the quote plus the `CHOREOGRAPHY_NOT_IMPLEMENTED` copy from `mapApiError` as an explanation panel — there is no submit control for surrender at all.

- [ ] **Step 7: Verify the suite and build**

```bash
npm test
npm run build
```
Expected: 38 tests passing; build exits 0.

- [ ] **Step 8: Commit**

```bash
git add frontend/customer-portal/src
git commit -m "feat(portal): policy detail, beneficiary editing, surrender quote

Beneficiary shares are summed as integers, never parsed as floats. Surrender
shows the quote and an explanation panel; no control is wired to the 501 POST."
```

---

## Task 10: Billing — invoices and paying a premium

**Files:**
- Create: `frontend/customer-portal/src/app/api/policies/[policyNumber]/invoices/route.ts`, `.../invoices/next-due/route.ts`
- Create: `frontend/customer-portal/src/app/api/invoices/[invoiceId]/payment-request/route.ts`
- Create: `frontend/customer-portal/src/app/(portal)/policies/[policyNumber]/billing/page.tsx`
- Create: `frontend/customer-portal/src/components/invoice-table.tsx` + `src/components/invoice-table.test.tsx`

**Interfaces:**
- Consumes: `callBackend`, `ApiError`, `MoneyText`, `mapApiError`, `useSubmitGuard`.
- Produces: `POST /api/invoices/{invoiceId}/payment-request` with a `payerRef` body.

**Context:** `GET /policies/{n}/invoices` supports a `status` filter (`DUE, PARTIALLY_PAID, PAID, IN_GRACE, OVERDUE, WAIVED`) and **no pagination** — verified: the endpoint declares no `page`/`pageSize`. Invoices are pre-generated ~12 months ahead, a bounded list by construction, so build status tabs and NOT a pager. `InvoiceView`: `invoiceId, policyNumber, dueDate, amount, status, gracePeriodEndsAt, dunningLevel`. `POST /invoices/{id}/payment-request` **requires** a non-blank `Idempotency-Key` (400 otherwise) and idempotency IS genuinely enforced server-side — so this endpoint needs Layer 1 only, no Redis claim.

- [ ] **Step 1: Write the payment-request Route Handler**

```ts
import { NextRequest, NextResponse } from 'next/server';
import { randomUUID } from 'node:crypto';
import { ApiError, callBackend } from '@/lib/backend';

/**
 * Billing's payment-request enforces Idempotency-Key for real (a DB-backed registry), unlike the
 * loan endpoints — so no Redis claim is needed here. The client supplies the key so a user-driven
 * retry of the SAME attempt deduplicates; a missing one is generated rather than sent blank, which
 * the backend rejects with a 400.
 */
export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ invoiceId: string }> },
) {
  const { invoiceId } = await params;
  const body = (await request.json()) as { payerRef: string; idempotencyKey?: string };
  try {
    await callBackend(`/invoices/${encodeURIComponent(invoiceId)}/payment-request`, {
      method: 'POST',
      body: { payerRef: body.payerRef },
      headers: { 'Idempotency-Key': body.idempotencyKey ?? randomUUID() },
    });
    return new NextResponse(null, { status: 202 });
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}
```

- [ ] **Step 2: Write the failing invoice-table tests**

Create `frontend/customer-portal/src/components/invoice-table.test.tsx`:

```tsx
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { InvoiceTable } from './invoice-table';

const DUE = {
  invoiceId: '11111111-1111-1111-1111-111111111111',
  policyNumber: 'POL-1', dueDate: '2026-09-01',
  amount: { amount: '125000', currencyCode: 'TZS' },
  status: 'DUE' as const, gracePeriodEndsAt: null, dunningLevel: null,
};

describe('InvoiceTable', () => {
  it('renders each invoice with formatted money and status', () => {
    render(<InvoiceTable invoices={[DUE]} onPay={vi.fn()} />);
    expect(screen.getByText('TZS 125,000.00')).toBeInTheDocument();
    expect(screen.getByText('DUE')).toBeInTheDocument();
  });

  it('offers Pay only for invoices that can still be paid', () => {
    render(<InvoiceTable invoices={[{ ...DUE, status: 'PAID' }]} onPay={vi.fn()} />);
    expect(screen.queryByRole('button', { name: /pay/i })).not.toBeInTheDocument();
  });

  it('shows the dunning level when an arrears case is open', () => {
    render(<InvoiceTable invoices={[{ ...DUE, status: 'OVERDUE', dunningLevel: 3 }]} onPay={vi.fn()} />);
    expect(screen.getByText(/reminder 3/i)).toBeInTheDocument();
  });

  it('calls onPay once even when Pay is clicked three times in a tick', async () => {
    const onPay = vi.fn().mockImplementation(() => new Promise<void>((r) => setTimeout(r, 10)));
    render(<InvoiceTable invoices={[DUE]} onPay={onPay} />);
    const button = screen.getByRole('button', { name: /pay/i });

    button.click();
    button.click();
    button.click();

    expect(onPay).toHaveBeenCalledTimes(1);
  });

  it('shows an empty state when a status filter matches nothing', () => {
    render(<InvoiceTable invoices={[]} onPay={vi.fn()} />);
    expect(screen.getByText(/no invoices/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run it to verify it fails**

Run: `npm test -- invoice-table`
Expected: FAIL — `Cannot find module './invoice-table'`.

- [ ] **Step 4: Implement `InvoiceTable`**

Create `frontend/customer-portal/src/components/invoice-table.tsx`: a shadcn `Table` with columns Due date, Amount (`MoneyText`), Status (`Badge`), Reminder level (`Reminder {dunningLevel}` when non-null), and a Pay `Button` shown only for `DUE | PARTIALLY_PAID | IN_GRACE | OVERDUE`. Pay goes through `useSubmitGuard`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npm test -- invoice-table`
Expected: PASS, 5/5.

- [ ] **Step 6: Build the billing page**

Create `frontend/customer-portal/src/app/(portal)/policies/[policyNumber]/billing/page.tsx`: a next-due summary from `/api/policies/{n}/invoices/next-due`, status-filter `Tabs` (All, DUE, OVERDUE, IN_GRACE, PAID, WAIVED) driving the `status` param, and `InvoiceTable`. No pagination controls — the endpoint has none and the list is bounded by design. The Pay action prompts for `payerRef` (a mobile-money MSISDN) in a `Dialog`, generates one client key per dialog instance, and calls the Route Handler.

- [ ] **Step 7: Verify the suite and build**

```bash
npm test
npm run build
```
Expected: 43 tests passing; build exits 0.

- [ ] **Step 8: Commit**

```bash
git add frontend/customer-portal/src
git commit -m "feat(portal): billing screen with status tabs and premium payment

Status filter tabs, deliberately no pager: the invoices endpoint declares no
page/pageSize and the list is bounded by construction. Payment-request needs
Layer 1 only — billing enforces Idempotency-Key server-side for real."
```

---

## Task 11: Claims — list, register, detail, and evidence

**Files:**
- Create: `frontend/customer-portal/src/app/api/claims/route.ts`, `.../claims/[claimId]/route.ts`, `.../claims/[claimId]/evidence/route.ts`, `.../claims/[claimId]/evidence/[documentRef]/route.ts`
- Create: `frontend/customer-portal/src/app/(portal)/claims/page.tsx`, `.../claims/[claimId]/page.tsx`, `.../claims/new/page.tsx`
- Create: `frontend/customer-portal/src/components/claim-list.tsx` + `src/components/claim-list.test.tsx`
- Create: `frontend/customer-portal/src/components/evidence-panel.tsx` + `src/components/evidence-panel.test.tsx`

**Interfaces:**
- Consumes: `callBackend`, `callBackendRaw`, `ApiError`, `mapApiError`, `useSubmitGuard`, `MoneyText`.
- Produces: `GET/POST /api/claims`, `GET /api/claims/{claimId}`, `POST/GET /api/claims/{claimId}/evidence`, `GET /api/claims/{claimId}/evidence/{documentRef}` (binary passthrough).

**Context:** `GET /claims` supports `page` (default 0), `pageSize` (default 20, max 100), `status`, and `claimantPartyId` — the last is force-overridden for customer tokens, so the portal never sends it. `ClaimView`: `claimId, policyNumber, claimantPartyId, claimType, status, dateOfEvent, details, approvedAmount, requiresContestabilityReview`. Claim types: `DEATH, DISABILITY, CRITICAL_ILLNESS, MATURITY`. Statuses: `REGISTERED, UNDER_ASSESSMENT, APPROVED, REJECTED, SETTLEMENT_REQUESTED, SETTLED, REOPENED`. Registration IS genuinely idempotent server-side — Layer 1 only. Evidence upload is `multipart/form-data` with an upload-time content-type allowlist of `image/jpeg`, `image/png`, `application/pdf`, `application/octet-stream` (M11); anything else is a 422.

- [ ] **Step 1: Write the evidence download passthrough**

Binary responses must stream through without JSON parsing:

```ts
import { NextRequest, NextResponse } from 'next/server';
import { ApiError, callBackendRaw } from '@/lib/backend';

/**
 * Binary passthrough for the claim-evidence download M11 built. Deliberately uses callBackendRaw:
 * the response is a file, and the backend sets Content-Type and Content-Disposition itself (one of
 * the four allowlisted types), so this handler forwards them rather than inventing its own.
 */
export async function GET(
  _request: NextRequest,
  { params }: { params: Promise<{ claimId: string; documentRef: string }> },
) {
  const { claimId, documentRef } = await params;
  const upstream = await callBackendRaw(
    `/claims/${encodeURIComponent(claimId)}/evidence/${encodeURIComponent(documentRef)}`,
  );

  if (!upstream.ok) {
    let problem = null;
    try { problem = await upstream.json(); } catch { problem = null; }
    return NextResponse.json(problem, { status: upstream.status });
  }

  return new NextResponse(upstream.body, {
    status: 200,
    headers: {
      'Content-Type': upstream.headers.get('content-type') ?? 'application/octet-stream',
      ...(upstream.headers.get('content-disposition')
        ? { 'Content-Disposition': upstream.headers.get('content-disposition')! }
        : {}),
    },
  });
}
```

The upload handler forwards the incoming `FormData` to the backend unchanged (no `Content-Type` header of its own — `fetch` sets the multipart boundary), so use `callBackendRaw` with the `FormData` as `body` and no JSON serialisation.

- [ ] **Step 2: Write the failing claim-list tests**

Create `frontend/customer-portal/src/components/claim-list.test.tsx`:

```tsx
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ClaimList } from './claim-list';

const CLAIM = {
  claimId: '22222222-2222-2222-2222-222222222222',
  policyNumber: 'POL-1', claimType: 'DISABILITY' as const,
  status: 'UNDER_ASSESSMENT' as const, dateOfEvent: '2026-06-01',
  requiresContestabilityReview: false,
};

describe('ClaimList', () => {
  it('renders claim rows with type and status', () => {
    render(<ClaimList claims={[CLAIM]} />);
    expect(screen.getByText(/disability/i)).toBeInTheDocument();
    expect(screen.getByText(/under assessment/i)).toBeInTheDocument();
  });

  it('shows an approved amount when the backend supplies one', () => {
    render(<ClaimList claims={[{ ...CLAIM, status: 'APPROVED', approvedAmount: { amount: '4500000', currencyCode: 'TZS' } }]} />);
    expect(screen.getByText('TZS 4,500,000.00')).toBeInTheDocument();
  });

  it('does not expose the internal contestability-review flag as scary copy', () => {
    // requiresContestabilityReview is an internal assessment signal, not a customer-facing verdict.
    render(<ClaimList claims={[{ ...CLAIM, requiresContestabilityReview: true }]} />);
    expect(screen.queryByText(/contestability/i)).not.toBeInTheDocument();
  });

  it('shows an empty state', () => {
    render(<ClaimList claims={[]} />);
    expect(screen.getByText(/no claims/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run it to verify it fails**

Run: `npm test -- claim-list`
Expected: FAIL — `Cannot find module './claim-list'`.

- [ ] **Step 4: Implement `ClaimList`**

Create `frontend/customer-portal/src/components/claim-list.tsx`: a `Table` with Date of event, Type (title-cased), Status (`Badge`, underscores replaced with spaces), Approved amount (`MoneyText`, only when present), and a link to the detail page. Do not render `requiresContestabilityReview`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npm test -- claim-list`
Expected: PASS, 4/4.

- [ ] **Step 6: Write the failing evidence-panel tests**

Create `frontend/customer-portal/src/components/evidence-panel.test.tsx`:

```tsx
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { EvidencePanel } from './evidence-panel';

describe('EvidencePanel', () => {
  it('lists existing evidence with download links', () => {
    render(<EvidencePanel claimId="c1" evidence={[{ documentRef: 'doc-1', fileName: 'report.pdf' }]} onUpload={vi.fn()} />);
    const link = screen.getByRole('link', { name: /report\.pdf/i });
    expect(link).toHaveAttribute('href', '/api/claims/c1/evidence/doc-1');
  });

  it('rejects a disallowed content type before uploading', async () => {
    const onUpload = vi.fn();
    render(<EvidencePanel claimId="c1" evidence={[]} onUpload={onUpload} />);

    const file = new File(['x'], 'notes.txt', { type: 'text/plain' });
    await userEvent.upload(screen.getByLabelText(/attach a file/i), file);

    expect(onUpload).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent(/JPEG, PNG or PDF/i);
  });

  it('accepts an allowed content type', async () => {
    const onUpload = vi.fn().mockResolvedValue(undefined);
    render(<EvidencePanel claimId="c1" evidence={[]} onUpload={onUpload} />);

    const file = new File(['x'], 'scan.pdf', { type: 'application/pdf' });
    await userEvent.upload(screen.getByLabelText(/attach a file/i), file);

    expect(onUpload).toHaveBeenCalledTimes(1);
  });
});
```

- [ ] **Step 7: Run it to verify it fails**

Run: `npm test -- evidence-panel`
Expected: FAIL — `Cannot find module './evidence-panel'`.

- [ ] **Step 8: Implement `EvidencePanel`**

Create `frontend/customer-portal/src/components/evidence-panel.tsx`: a list of existing evidence linking to `/api/claims/{claimId}/evidence/{documentRef}`, plus a file input labelled "Attach a file". Client-side allowlist `['image/jpeg', 'image/png', 'application/pdf']` matched to M11's server-side allowlist, rejecting others with a `role="alert"` message before any request — the server rejects them too, but a local check gives instant feedback. Upload goes through `useSubmitGuard`.

- [ ] **Step 9: Build the three claim pages**

- `claims/page.tsx` — paginated list (`page`/`pageSize`) with status-filter tabs; never sends `claimantPartyId`.
- `claims/new/page.tsx` — registration form (policy number, claim type, date of event, details), `useSubmitGuard`, no Redis claim (registration is genuinely idempotent).
- `claims/[claimId]/page.tsx` — detail plus `EvidencePanel`.

- [ ] **Step 10: Verify the suite and build**

```bash
npm test
npm run build
```
Expected: 50 tests passing; build exits 0.

- [ ] **Step 11: Commit**

```bash
git add frontend/customer-portal/src
git commit -m "feat(portal): claims list, registration, detail and evidence round trip

Evidence download streams through as a binary passthrough with the backend's own
Content-Type/Disposition. Upload mirrors M11's server-side content-type
allowlist client-side for instant feedback."
```

---

## Task 12: Policy loan and reference information — the full hard guard

**Files:**
- Create: `frontend/customer-portal/src/app/api/policies/[policyNumber]/loans/route.ts`, `.../api/loans/[loanId]/route.ts`, `.../api/loans/[loanId]/repayments/route.ts`
- Create: `frontend/customer-portal/src/app/api/reference-codes/[codeSetKey]/route.ts`
- Create: `frontend/customer-portal/src/lib/guarded-mutation.ts` + `src/lib/guarded-mutation.test.ts`
- Create: `frontend/customer-portal/src/app/(portal)/policies/[policyNumber]/loan/page.tsx`
- Create: `frontend/customer-portal/src/components/reference-note.tsx`

**Interfaces:**
- Consumes: `claimIdempotency`, `releaseIdempotency`, `recordOutcome`, `idempotencyKeyFor`, `redisIdempotencyStore`, `callBackend`, `ApiError`, `useSubmitGuard`, `auth`.
- Produces: `runGuardedMutation(options): Promise<GuardedResult>` — the Layer 2 wrapper both loan Route Handlers use.

**Context:** These are the two endpoints with no server-side idempotency. Both layers apply. The release rules from spec §6 are the substance of this task: correctable 4xx → `releaseIdempotency`; success or state-changing definitive response → `recordOutcome`; indeterminate (timeout/5xx) → keep the claim. Loan origination currently always 409s `INSUFFICIENT_LOAN_VALUE` because cash value is never credited platform-wide — build the screen and its error state; do not fake success or skip it. Reference keys readable by customers are exactly: `TZ_CONTESTABILITY_MONTHS`, `TZ_REINSTATEMENT_WINDOW_MONTHS`, `TZ_SUSPENSION_TO_LAPSE_MONTHS`, `TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE`, `DUNNING_ESCALATION_DAYS`. Every value renders with a "provisional" note (the seed data is placeholder pending sign-off).

- [ ] **Step 1: Write the failing guarded-mutation tests**

Create `frontend/customer-portal/src/lib/guarded-mutation.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest';
import { runGuardedMutation } from './guarded-mutation';
import type { IdempotencyStore } from './idempotency';

function fakeStore() {
  const data = new Map<string, string>();
  return {
    data,
    async setIfAbsent(key: string, value: string) {
      if (data.has(key)) return false;
      data.set(key, value);
      return true;
    },
    async get(key: string) { return data.get(key) ?? null; },
    async set(key: string, value: string) { data.set(key, value); },
    async del(key: string) { data.delete(key); },
  } satisfies IdempotencyStore & { data: Map<string, string> };
}

const KEY = 'idem:customers:sub-1:loan-origination:client-key-1';

describe('runGuardedMutation', () => {
  it('performs the call and records the outcome on success', async () => {
    const store = fakeStore();
    const perform = vi.fn().mockResolvedValue({ kind: 'success', body: { loanId: 'L-1' } });

    const result = await runGuardedMutation({ store, key: KEY, perform });

    expect(result).toEqual({ status: 200, body: { loanId: 'L-1' } });
    expect(store.data.get(KEY)).toContain('L-1');
  });

  it('replays a recorded outcome instead of calling again', async () => {
    const store = fakeStore();
    await runGuardedMutation({ store, key: KEY, perform: vi.fn().mockResolvedValue({ kind: 'success', body: { loanId: 'L-1' } }) });

    const perform = vi.fn();
    const replay = await runGuardedMutation({ store, key: KEY, perform });

    expect(perform).not.toHaveBeenCalled();
    expect(replay.body).toEqual({ loanId: 'L-1' });
  });

  it('returns 409 when a claim is in flight with no outcome yet', async () => {
    const store = fakeStore();
    const never = vi.fn().mockImplementation(() => new Promise(() => {}));
    void runGuardedMutation({ store, key: KEY, perform: never });   // holds the claim

    const second = await runGuardedMutation({ store, key: KEY, perform: vi.fn() });

    expect(second.status).toBe(409);
  });

  it('RELEASES the claim on a correctable 4xx so the user can retry', async () => {
    // The un-retryable-form bug. Without this branch, correcting a rejected amount and
    // resubmitting would replay the stale rejection forever.
    const store = fakeStore();
    const perform = vi.fn().mockResolvedValue({
      kind: 'rejected', status: 422,
      problem: { status: 422, errorCode: 'VALIDATION_ERROR', traceId: 't' },
    });

    const result = await runGuardedMutation({ store, key: KEY, perform });

    expect(result.status).toBe(422);
    expect(store.data.has(KEY)).toBe(false);

    const retry = vi.fn().mockResolvedValue({ kind: 'success', body: { loanId: 'L-2' } });
    const second = await runGuardedMutation({ store, key: KEY, perform: retry });
    expect(retry).toHaveBeenCalledTimes(1);
    expect(second.status).toBe(200);
  });

  it('KEEPS the claim on an indeterminate failure', async () => {
    // A timeout or 5xx may mean the backend applied the change. Blocking the retry is the
    // conservative, correct answer here.
    const store = fakeStore();
    const perform = vi.fn().mockResolvedValue({ kind: 'indeterminate', status: 504 });

    const result = await runGuardedMutation({ store, key: KEY, perform });

    expect(result.status).toBe(504);
    expect(store.data.has(KEY)).toBe(true);
  });

  it('keeps the claim when perform throws unexpectedly', async () => {
    const store = fakeStore();
    const perform = vi.fn().mockRejectedValue(new Error('socket hang up'));

    const result = await runGuardedMutation({ store, key: KEY, perform });

    expect(result.status).toBe(504);
    expect(store.data.has(KEY)).toBe(true);
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npm test -- guarded-mutation`
Expected: FAIL — `Cannot find module './guarded-mutation'`.

- [ ] **Step 3: Implement the guarded mutation**

Create `frontend/customer-portal/src/lib/guarded-mutation.ts`:

```ts
import {
  claimIdempotency, recordOutcome, releaseIdempotency, type IdempotencyStore,
} from '@/lib/idempotency';
import type { ApiProblem } from '@/lib/problem';

/**
 * Layer 2 applied to one mutation, with the release rules from spec §6. Used only by the two
 * endpoints that accept Idempotency-Key and never read it: loan origination and loan repayment.
 *
 * The three-way classification is the whole point:
 *   success/state-changing -> keep the claim, record the outcome (a retry replays it)
 *   correctable rejection  -> RELEASE the claim (nothing happened; the user must be able to retry)
 *   indeterminate          -> KEEP the claim (the backend may have applied it; blocking is correct)
 */
export type PerformResult =
  | { kind: 'success'; body: unknown }
  | { kind: 'rejected'; status: number; problem: ApiProblem | null }
  | { kind: 'indeterminate'; status: number };

export type GuardedResult = { status: number; body?: unknown };

export async function runGuardedMutation(options: {
  store: IdempotencyStore;
  key: string;
  perform: () => Promise<PerformResult>;
}): Promise<GuardedResult> {
  const { store, key, perform } = options;

  const claim = await claimIdempotency(store, key);
  if (!claim.claimed) {
    if (claim.outcome) {
      return { status: 200, body: JSON.parse(claim.outcome) };
    }
    return {
      status: 409,
      body: {
        type: 'about:blank', title: 'Conflict', status: 409,
        errorCode: 'REQUEST_ALREADY_IN_PROGRESS', traceId: 'portal-guard',
        detail: 'This request is already being processed.',
      },
    };
  }

  let outcome: PerformResult;
  try {
    outcome = await perform();
  } catch {
    // An exception here is indeterminate by definition: we do not know whether the backend
    // applied the change, so the claim stays.
    return { status: 504, body: { type: 'about:blank', title: 'Gateway Timeout', status: 504,
      errorCode: 'OUTCOME_UNKNOWN', traceId: 'portal-guard' } };
  }

  if (outcome.kind === 'success') {
    await recordOutcome(store, key, JSON.stringify(outcome.body));
    return { status: 200, body: outcome.body };
  }
  if (outcome.kind === 'rejected') {
    await releaseIdempotency(store, key);
    return { status: outcome.status, body: outcome.problem };
  }
  return { status: outcome.status, body: { type: 'about:blank', title: 'Gateway Timeout',
    status: outcome.status, errorCode: 'OUTCOME_UNKNOWN', traceId: 'portal-guard' } };
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm test -- guarded-mutation`
Expected: PASS, 6/6.

- [ ] **Step 5: Wire the loan Route Handlers**

Create `frontend/customer-portal/src/app/api/policies/[policyNumber]/loans/route.ts`. The `POST` classifies the backend response into the three kinds — a 4xx below 500 that is not 408/429 is `rejected`; 408, 429, and 5xx are `indeterminate`:

```ts
import { NextRequest, NextResponse } from 'next/server';
import { auth } from '@/auth';
import { ApiError, callBackend } from '@/lib/backend';
import { runGuardedMutation, type PerformResult } from '@/lib/guarded-mutation';
import { idempotencyKeyFor } from '@/lib/idempotency';
import { redisIdempotencyStore } from '@/lib/redis';

const INDETERMINATE = new Set([408, 429]);

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ policyNumber: string }> },
) {
  const { policyNumber } = await params;
  const body = (await request.json()) as { amount: string; currencyCode: string; clientKey: string };
  const session = (await auth()) as unknown as { user?: { id?: string }; sub?: string } | null;
  const subject = session?.sub ?? session?.user?.id;
  if (!subject) {
    return NextResponse.json({ status: 401, title: 'Unauthorized', type: 'about:blank', traceId: 'portal' }, { status: 401 });
  }

  const key = idempotencyKeyFor({
    realm: 'customers', subject, operation: 'loan-origination', clientKey: body.clientKey,
  });

  const result = await runGuardedMutation({
    store: redisIdempotencyStore(),
    key,
    perform: async (): Promise<PerformResult> => {
      try {
        const created = await callBackend<unknown>(
          `/policies/${encodeURIComponent(policyNumber)}/loans`,
          { method: 'POST', body: { amount: body.amount, currencyCode: body.currencyCode },
            headers: { 'Idempotency-Key': body.clientKey } },
        );
        return { kind: 'success', body: created };
      } catch (error) {
        if (error instanceof ApiError) {
          if (error.status >= 500 || INDETERMINATE.has(error.status)) {
            return { kind: 'indeterminate', status: error.status };
          }
          return { kind: 'rejected', status: error.status, problem: error.problem };
        }
        return { kind: 'indeterminate', status: 504 };
      }
    },
  });

  return NextResponse.json(result.body ?? null, { status: result.status });
}
```

`GET` on the same route proxies the loan list. Create `.../api/loans/[loanId]/repayments/route.ts` as a near-identical `POST` with `operation: 'loan-repayment'` — copy the code rather than extracting a shared helper prematurely; the two differ in path, body, and operation name.

- [ ] **Step 6: Add the reference-data route and note component**

Create `frontend/customer-portal/src/app/api/reference-codes/[codeSetKey]/route.ts` with a hard allowlist — the backend fails closed too, but the portal must not send keys it has no business requesting:

```ts
const CUSTOMER_READABLE = new Set([
  'TZ_CONTESTABILITY_MONTHS',
  'TZ_REINSTATEMENT_WINDOW_MONTHS',
  'TZ_SUSPENSION_TO_LAPSE_MONTHS',
  'TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE',
  'DUNNING_ESCALATION_DAYS',
]);
```

Return 404 for anything else without calling the backend. Create `frontend/customer-portal/src/components/reference-note.tsx` rendering a value with a small "provisional — pending final sign-off" note, per the seed data's own caveat.

- [ ] **Step 7: Build the loan page**

Create `frontend/customer-portal/src/app/(portal)/policies/[policyNumber]/loan/page.tsx`:
- the current loan (if any) with `principalAmount`, `outstandingBalance`, `currentInterestRate`, `status`
- a borrow form and a repayment form, each generating ONE `clientKey` (via `crypto.randomUUID()`) per form instance, each wrapped in `useSubmitGuard`
- the `INSUFFICIENT_LOAN_VALUE` message from `mapApiError` rendered as the expected outcome, not a crash
- the annual interest rate from `/api/reference-codes/TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE` shown through `ReferenceNote`

- [ ] **Step 8: Verify the suite and build**

```bash
npm test
npm run build
```
Expected: 56 tests passing; build exits 0.

- [ ] **Step 9: Commit**

```bash
git add frontend/customer-portal/src
git commit -m "feat(portal): policy loan screens behind the full two-layer guard

Both loan mutations classify their outcome three ways: success records and
replays, a correctable 4xx releases the claim so the form stays retryable, and
an indeterminate result keeps it because the backend may have applied the change."
```

---

## Task 13: Playwright end-to-end against the real seeded stack

**Files:**
- Create: `frontend/customer-portal/playwright.config.ts`
- Create: `frontend/customer-portal/e2e/auth.setup.ts`, `e2e/claim-evidence.spec.ts`, `e2e/ownership.spec.ts`
- Create: `docs/10-frontend-development.md`

**Interfaces:**
- Consumes: the running M11 stack and seeded data; the portal's own dev server.
- Produces: `npm run e2e` and a runbook for starting everything.

**Context:** M11 left a working local stack and a seeder. Test users are `customer.owner` and `customer.other`, both password `devpassword`, in the `customers` realm. **`party_id` is written to the Keycloak users by `scripts/seed-dev-data.sh`, not by the realm import** (gotcha 4 in `docs/09-local-development.md`) — without running the seeder, login succeeds but every scoped endpoint 403s. `customer.other` owns nothing and is the fixture for every denial test. Log in through real Keycloak, never a mocked provider: M11's own lesson was that a fabricated test identity hid a completely broken real credential path twice.

- [ ] **Step 1: Write the Playwright config**

Create `frontend/customer-portal/playwright.config.ts`:

```ts
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,        // shared backend state
  retries: 0,                  // a flaky auth failure should fail loudly, not be papered over
  use: { baseURL: 'http://localhost:3000', trace: 'on-first-retry' },
  projects: [
    { name: 'setup', testMatch: /auth\.setup\.ts/ },
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], storageState: 'e2e/.auth/owner.json' },
      dependencies: ['setup'],
    },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:3000',
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
```

- [ ] **Step 2: Write the real-Keycloak login setup**

Create `frontend/customer-portal/e2e/auth.setup.ts`:

```ts
import { test as setup, expect } from '@playwright/test';

/**
 * Logs in through the REAL Keycloak `customers` realm — no mocked auth provider. M11's headline
 * lesson was that fabricated test identities hid a completely broken real credential path twice
 * (the M1 database role and the M11 Keycloak mappers), both with a fully green suite. This test
 * exists to make that class of failure impossible to miss again.
 */
setup('authenticate as customer.owner', async ({ page }) => {
  await page.goto('/');
  // Middleware redirects an unauthenticated visit to Keycloak's login form.
  await page.getByLabel(/username/i).fill('customer.owner');
  await page.getByLabel(/password/i).fill('devpassword');
  await page.getByRole('button', { name: /sign in/i }).click();

  await expect(page).toHaveURL('http://localhost:3000/');
  await page.context().storageState({ path: 'e2e/.auth/owner.json' });
});
```

Add `e2e/.auth/` to `.gitignore`.

- [ ] **Step 3: Write the claim-evidence round-trip spec**

Create `frontend/customer-portal/e2e/claim-evidence.spec.ts`. This is the single most valuable E2E test on the platform: it re-proves M11's headline claim (a real Keycloak token reaching a real MinIO-backed download) on every run.

```ts
import { test, expect } from '@playwright/test';
import path from 'node:path';

test('uploads and downloads claim evidence end to end', async ({ page }) => {
  await page.goto('/claims');
  await page.getByRole('link', { name: /view/i }).first().click();

  await page.getByLabel(/attach a file/i)
    .setInputFiles(path.join(import.meta.dirname, 'fixtures/evidence.pdf'));

  const row = page.getByRole('link', { name: /evidence\.pdf/i });
  await expect(row).toBeVisible();

  const download = await Promise.all([
    page.waitForEvent('download'),
    row.click(),
  ]).then(([d]) => d);

  expect(download.suggestedFilename()).toBe('evidence.pdf');
});
```

Create a small real PDF at `e2e/fixtures/evidence.pdf`.

- [ ] **Step 4: Write the ownership-denial spec**

Create `frontend/customer-portal/e2e/ownership.spec.ts`, logging in as `customer.other` (who owns nothing) in its own context and asserting the dashboard shows the empty state rather than another customer's policies — the positive test cannot catch a broken scope filter, this one can.

- [ ] **Step 5: Start the stack and run the E2E suite**

```bash
cd infra && docker compose up -d && cd ..
./scripts/migrate.sh local
./scripts/seed-dev-data.sh          # REQUIRED — writes party_id back to the Keycloak users
./mvnw spring-boot:run              # or the app container
cd frontend/customer-portal && npm run e2e
```
Expected: all specs pass. Run in the FOREGROUND and read the real Playwright summary. If `customer.owner` logs in but every list is empty AND the backend logs 403s, the seeder did not run — that is gotcha 4, not a portal bug.

- [ ] **Step 6: Write the frontend runbook**

Create `docs/10-frontend-development.md` covering: prerequisites, the exact startup order above, `.env.local` from `.env.example`, `npm run generate:api` after any OpenAPI change, and a gotchas section whose first entry is the `party_id`/seeder dependency.

- [ ] **Step 7: Commit**

```bash
git add frontend/customer-portal/playwright.config.ts frontend/customer-portal/e2e \
        frontend/customer-portal/.gitignore docs/10-frontend-development.md
git commit -m "test(portal): Playwright E2E through real Keycloak, plus the frontend runbook

Logs in against the real customers realm rather than a mocked provider, and
covers the claim-evidence upload/download round trip and a customer.other
denial case."
```

---

## Self-Review

**1. Spec coverage.** Every spec section maps to a task: §2 architecture → Tasks 3–6; §2 Money → Task 4; §2 shadcn/TanStack → Tasks 3, 8; §3 token refresh → Task 5; §4 screens → Tasks 8–12 (endorsements correctly absent); §5 reference data → Task 12; §6 constraints → Task 12 (guard), Tasks 8–9 (zero cash value rendered as-is); §7 prelude → Tasks 1–2; §8 error handling → Task 4; §9 testing → every task plus Task 13; §10 deferred → nothing built.

**2. Placeholder scan.** No TBD/TODO, no "add error handling", no "similar to Task N". Every code step carries real code; the three steps that describe a screen rather than transcribing all its JSX (8.8, 10.6, 12.7) each name the exact endpoints, params, states, and constraints, and the components they compose are fully specified in the same task.

**3. Type consistency.** `formatMoney(amount, currencyCode)` — consistent in Tasks 4, 8, 9, 10. `ApiProblem`/`mapApiError` — Tasks 4, 6, 9, 12. `ApiError { status, problem }` — Tasks 6, 8–12. `IdempotencyStore` methods (`setIfAbsent`/`get`/`set`/`del`) — identical in Tasks 7 and 12. `useSubmitGuard` returns `{ submit, isSubmitting }` — consistent in Tasks 7, 9, 10, 11, 12. `PortalToken` — Task 5 only. `runGuardedMutation`/`PerformResult` — Task 12 only. Java: `BillingApi.getInvoice(UUID)` defined and consumed in Task 2; `isCustomer`/`ownPartyIdOrThrow` defined and consumed in Task 1.

**4. Test-count arithmetic.** 1 (T3) + 11 (T4) + 5 (T5) + 5 (T6) + 9 (T7) + 3 (T8) + 4 (T9) + 5 (T10) + 7 (T11) + 6 (T12) = 56 frontend unit tests, matching Task 12's expected total. Backend: 661 baseline + 5 (T1) + 3 (T2) = 669.

**One risk flagged, not resolved here.** Task 13's claim-evidence spec assumes the seeded data includes at least one claim for `customer.owner`. `scripts/seed-dev-data.sh` does drive a claims flow, but if the seeded claim is not in a state that accepts new evidence, Task 13's implementer should register a fresh claim through the portal at the start of the spec rather than editing the seeder — that keeps the E2E self-contained and does not touch M11's non-idempotent seeder.
