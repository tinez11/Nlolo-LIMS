# Universal Login — Step 1: One Realm, One Sign-in — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the four Keycloak realms with one realm `lifeplatform`, so everyone signs in on one page and the console opens the workspace(s) their account holds.

**Architecture:** Keycloak holds one realm with four *workspace roles* (`staff`, `agent`, `customer`, `regulator`) beside the existing staff job roles. The backend validates one issuer and turns `X-Workspace` + the token's roles into exactly one of the existing `ROLE_REALM_*` authorities, so the ~185 existing `hasRole('REALM_…')` checks stay untouched. The console mounts one OIDC provider at the root; `/` signs in and routes by workspace; the existing `/staff`, `/agents`, `/customers` URL prefixes stay as *workspace* paths and the request header is derived from the path.

**Tech Stack:** Keycloak 26.0.7, Spring Boot 3 / Spring Security resource server, React + react-oidc-context + oidc-client-ts, Playwright, Node (realm tooling).

Spec: `backend/docs/superpowers/specs/2026-11-06-universal-login-design.md`. Steps 2 (resets/notices) and 3 (change password) get their own plans after this merges.

## Global Constraints

- Realm name `lifeplatform`; workspace roles exactly `staff`, `agent`, `customer`, `regulator` (lower case); header `X-Workspace` with those same four values.
- Workspace → authority: `staff`→`ROLE_REALM_STAFF`, `agent`→`ROLE_REALM_AGENTS`, `customer`→`ROLE_REALM_CUSTOMERS`, `regulator`→`ROLE_REALM_REGULATORS`. Never more than one `ROLE_REALM_*` per request.
- Job roles (every realm role that is not a workspace role) are granted only when the active workspace is `staff` or `regulator`; none in `agent`/`customer`.
- Header absent + exactly one workspace role → that workspace. Header absent + several → 400. Header names a workspace not held (or an unknown value) → 403. No workspace role at all → 403.
- U10: a staff account never holds another workspace; agent + customer combine on one account only via a matching `party_id`.
- Sign-in identifier: username or email (`loginWithEmailAllowed: true`, `duplicateEmailsAllowed: false`).
- Password policy unchanged: `length(8) and notUsername(undefined) and notEmail(undefined) and passwordHistory(3)`; brute-force: failureFactor 5, waitIncrementSeconds 60, maxFailureWaitSeconds 900, permanentLockout false.
- Never hardcode a production hostname (`APP_DOMAIN` / `AUTH_DOMAIN` only).
- Edit files with Write/Edit, never shell heredocs/sed (memory: feedback_edit_files_with_tools_not_shell). Never run Prettier.
- Maven on the host only: `JAVA_HOME=~/.jdks/jdk-21.0.12.1+1 ./mvnw …` from `backend/`. One heavy run at a time (Maven, vitest, Playwright never concurrently). Stop the dev backend before `clean test`.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## File Map

| File | Change | Responsibility |
|---|---|---|
| `backend/keycloak/lifeplatform-realm.json` | create (generated once) | the one dev realm |
| `backend/keycloak/{staff,agents,customers,regulators}-realm.json` | delete | old realms |
| `backend/scripts/apply-*.{sh,mjs}` (6 files) | delete | patched old realms on existing dev Keycloaks; obsolete |
| `backend/src/main/java/.../iam/infrastructure/Workspace.java` | create | workspace enum + resolution rule (pure) |
| `backend/src/main/java/.../iam/infrastructure/WorkspaceFilter.java` | create | 400/403 ProblemDetail for unresolvable workspace |
| `backend/src/main/java/.../iam/infrastructure/SecurityConfig.java` | modify | one issuer, per-request converter |
| `backend/src/main/java/.../ProductionSettingsGuard.java` | modify | guard `app.security.issuer` |
| `backend/src/main/resources/application.yml` | modify | one issuer, one admin client |
| `backend/src/main/java/.../distribution/infrastructure/KeycloakAgentLogins.java` | modify | join by party, grant/revoke `agent` |
| `backend/src/main/java/.../omnichannel/infrastructure/KeycloakCustomerAccounts.java` | modify | join by party, grant/revoke `customer` |
| `backend/src/main/java/.../iam/infrastructure/KeycloakStaffLogins.java` | modify | grant `staff` on create |
| `backend/src/main/java/.../distribution/application/AgentLogins.java`, `omnichannel/application/CustomerAccounts.java` | modify (javadoc) | port semantics |
| `backend/infra/docker-compose.yml` | modify | one issuer env, one admin secret |
| `backend/scripts/seed-lib.sh`, `seed-dev-data.sh` (+ demo scripts calling `token_for`/`write_party_id`) | modify | one realm |
| `frontend/src/auth/realms.ts` | modify | issuer, workspace roles, path→workspace |
| `frontend/src/auth/PlatformAuthProvider.tsx` | create (replaces `RealmAuthProvider.tsx`) | one OIDC provider |
| `frontend/src/auth/RequireAuth.tsx`, `userStore.ts` | modify | realm-less |
| `frontend/src/auth/RequireWorkspace.tsx` | create | bounce a subtree the account lacks |
| `frontend/src/features/WorkspaceHome.tsx` | create (replaces `RealmPicker.tsx`) | one/several/none workspaces after sign-in |
| `frontend/src/entryPath.ts`, `entryPath.test.ts` | modify | drop `entryPath`/`SHOW_REALM_PICKER`, keep `hasRealmFallback` |
| `frontend/src/lib/http.ts` | modify | `X-Workspace` header |
| `frontend/src/App.tsx` | modify | root provider, `/` = sign-in |
| `frontend/src/components/AppShell.tsx` | modify | "Switch to …" in the account card |
| `frontend/e2e/*.setup.ts`, `agentSession.ts`, `creditLife.ts`, `staff-client-kyc-lifecycle.spec.ts` | modify | one realm |
| `deploy/keycloak/build-realms.mjs`, `build-realms.test.mjs` | modify | one production realm |
| `deploy/server/compose.yml`, `smoke.sh`, `add-user.sh`, `deploy/web/Dockerfile`, `deploy/README.md` | modify | one realm |
| `backend/docs/04-api-contracts.md` §3, `07-…`, `09-…`, `10-…`, `backend/keycloak/README.md` | modify | docs |

---

### Task 1: The one dev realm

**Files:**
- Create: `backend/keycloak/lifeplatform-realm.json`
- Delete: `backend/keycloak/staff-realm.json`, `agents-realm.json`, `customers-realm.json`, `regulators-realm.json`
- Delete: `backend/scripts/apply-customer-portal.mjs`, `apply-finance-approver.sh`, `apply-home-branch.sh`, `apply-spa-client.sh`, `apply-staff-admin.mjs`, `apply-staff-user-admin.mjs`

**Interfaces:**
- Produces: realm `lifeplatform`; clients `lifeplatform-spa` (public PKCE), `lifeplatform-app` (confidential, direct grants, secret `dev-secret-app`), `lifeplatform-admin` (service account, secret `dev-secret-admin`, realm-management `manage-users` + `view-users`); token claims `tenant_id`, `party_id`, `home_branch`, `realm_access.roles`; seed users with password `devpassword`: `staff.underwriter`, `staff.senior`, `staff.assessor`, `staff.manager`, `staff.finance`, `staff.finance-approver`, `staff.service`, `staff.admin` (role `staff` + their job roles), `agent.senior`, `agent.junior` (`agent`), `customer.owner`, `customer.other` (`customer`), `regulator.tira` (`regulator`, `TIRA_READ_ONLY`).

- [ ] **Step 1: Write the one-off merge script in the scratchpad** (not committed — the JSON it produces is the source from now on)

`<scratchpad>/merge-realms.mjs`:

```js
// One-off: merges backend/keycloak/{staff,agents,customers,regulators}-realm.json into lifeplatform-realm.json.
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const DIR = process.argv[2]; // backend/keycloak
const load = (n) => JSON.parse(readFileSync(join(DIR, `${n}-realm.json`), 'utf8'));
const staff = load('staff'), agents = load('agents'), customers = load('customers'), regulators = load('regulators');

const WORKSPACES = [
  ['staff', 'Insurer staff: the staff console. Job roles apply only here.'],
  ['agent', 'Agents: the agent console.'],
  ['customer', 'Policyholders: the customer portal.'],
  ['regulator', 'Regulators: regulatory returns.'],
];

const mapper = (claim) => ({
  name: claim, protocol: 'openid-connect', protocolMapper: 'oidc-usermodel-attribute-mapper', consentRequired: false,
  config: { 'user.attribute': claim, 'claim.name': claim, 'jsonType.label': 'String',
    'id.token.claim': 'true', 'access.token.claim': 'true', 'userinfo.token.claim': 'true' },
});
const MAPPERS = ['tenant_id', 'party_id', 'home_branch'].map(mapper);
const strip = (o) => { const c = structuredClone(o); delete c.id; return c; };

const spa = strip(customers.clients.find((c) => c.clientId === 'lifeplatform-spa'));
spa.protocolMappers = MAPPERS;
const app = strip(staff.clients.find((c) => c.clientId === 'lifeplatform-app'));
app.secret = 'dev-secret-app';
app.protocolMappers = MAPPERS;
const admin = strip(customers.clients.find((c) => c.clientId === 'lifeplatform-admin'));
admin.secret = 'dev-secret-admin';

const withRoles = (users, ...roles) => users
  .filter((u) => !u.username.startsWith('service-account-'))
  .map((u) => { const c = strip(u); c.realmRoles = [...new Set([...(u.realmRoles ?? []), ...roles])]; return c; });
const serviceAccount = strip(customers.users.find((u) => u.username === 'service-account-lifeplatform-admin'));

const realm = structuredClone(customers); // carries authenticationFlows + resetCredentialsFlow + components
delete realm.id;
realm.realm = 'lifeplatform';
realm.displayName = 'Nlolo Life';
realm.loginWithEmailAllowed = true;
realm.duplicateEmailsAllowed = false; // Keycloak's field name; `duplicateEmails` is refused at import
realm.roles = { realm: [
  ...WORKSPACES.map(([name, description]) => ({ name, description })),
  ...staff.roles.realm.map(strip),
  ...regulators.roles.realm.map(strip),
] };
realm.clients = [app, spa, admin];
realm.users = [
  ...withRoles(staff.users, 'staff'),
  ...withRoles(agents.users, 'agent'),
  ...withRoles(customers.users, 'customer'),
  ...withRoles(regulators.users, 'regulator'),
  serviceAccount,
];

const names = realm.users.map((u) => u.username);
const emails = realm.users.map((u) => u.email).filter(Boolean);
if (new Set(names).size !== names.length) throw new Error('duplicate username');
if (new Set(emails).size !== emails.length) throw new Error('duplicate email');
writeFileSync(join(DIR, 'lifeplatform-realm.json'), JSON.stringify(realm, null, 2) + '\n');
console.log(`users=${names.length} roles=${realm.roles.realm.length} clients=${realm.clients.length}`);
```

- [ ] **Step 2: Run it**

Run: `node "<scratchpad>/merge-realms.mjs" backend/keycloak`
Expected: `users=14 roles=13 clients=3` (8 staff + 2 agents + 2 customers + 1 regulator + 1 service account; 4 workspace + 8 staff + 1 TIRA role).

- [ ] **Step 3: Inspect the output by hand**

Open `backend/keycloak/lifeplatform-realm.json` and confirm: `resetCredentialsFlow` is `"lifeplatform reset credentials"`; `authenticationFlows` contains both lifeplatform flows; every non-service user has `credentials` with `devpassword`; `agent.senior` has `realmRoles: ["agent"]`; `staff.admin` has `"staff"` plus its six job roles; the service-account user has `clientRoles.realm-management: ["manage-users","view-users"]` and `serviceAccountClientId: "lifeplatform-admin"`.

- [ ] **Step 4: Delete the old realms and the obsolete apply scripts**

```bash
git rm backend/keycloak/staff-realm.json backend/keycloak/agents-realm.json backend/keycloak/customers-realm.json backend/keycloak/regulators-realm.json
git rm backend/scripts/apply-customer-portal.mjs backend/scripts/apply-finance-approver.sh backend/scripts/apply-home-branch.sh backend/scripts/apply-spa-client.sh backend/scripts/apply-staff-admin.mjs backend/scripts/apply-staff-user-admin.mjs
git grep -n "apply-customer-portal\|apply-finance-approver\|apply-home-branch\|apply-spa-client\|apply-staff-admin\|apply-staff-user-admin" -- ':!backend/docs/superpowers'
```

Expected from the grep: only README/doc lines, which Task 8 rewrites. Note them.

- [ ] **Step 5: Import it into the dev Keycloak and prove a password grant**

```bash
cd backend/infra && docker compose restart keycloak
# wait until healthy, then:
curl -s -X POST http://localhost:8081/realms/lifeplatform/protocol/openid-connect/token \
  -d grant_type=password -d client_id=lifeplatform-app -d client_secret=dev-secret-app \
  -d username=staff.admin -d password=devpassword | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const t=JSON.parse(s).access_token;const p=JSON.parse(Buffer.from(t.split('.')[1],'base64url'));console.log(p.iss,p.realm_access.roles.sort().join(','),p.tenant_id)})"
```

Expected: `http://localhost:8081/realms/lifeplatform` then a role list containing `ADMIN` and `staff`, then `11111111-1111-1111-1111-111111111111`. (No seed user has an email; sign-in by email is proved in Task 6's browser check.)

- [ ] **Step 6: Remove the four old realms from the dev Keycloak database** (import never deletes)

```bash
docker exec infra-keycloak-1 /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user admin --password devadmin
for r in staff agents customers regulators; do docker exec infra-keycloak-1 /opt/keycloak/bin/kcadm.sh delete realms/$r; done
```

(Check the container name with `docker ps` first.)

- [ ] **Step 7: Commit**

```bash
git add backend/keycloak/lifeplatform-realm.json
git commit -m "feat(keycloak): one realm, lifeplatform, with four workspace roles

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Backend — one issuer, one workspace per request

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/iam/infrastructure/Workspace.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/iam/infrastructure/WorkspaceFilter.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/iam/infrastructure/SecurityConfig.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/ProductionSettingsGuard.java:32-33`
- Modify: `backend/src/main/resources/application.yml:129-134`
- Modify: `backend/infra/docker-compose.yml:175-178`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/iam/infrastructure/WorkspaceTest.java` (create)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/iam/infrastructure/SecurityConfigTest.java` (rewrite)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/ProductionSettingsGuardTest.java` (follow the property rename)

**Interfaces:**
- Produces: `Workspace` enum (`STAFF`, `AGENT`, `CUSTOMER`, `REGULATOR`) with `role()`, `marker()`; `Workspace.HEADER = "X-Workspace"`; `Workspace.resolve(Collection<String> roles, String header)` returning `Workspace.Resolution` (`Active(Workspace)`, `Ambiguous(Set<Workspace>)`, `NotHeld(String)`, `NoWorkspace()`); `SecurityConfig.authoritiesFor(Jwt, String header)`; property `app.security.issuer`.

- [ ] **Step 1: Write the failing resolution tests**

`WorkspaceTest.java`:

```java
package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceTest {

    @Test
    void theHeaderPicksAWorkspaceTheTokenHolds() {
        assertThat(Workspace.resolve(List.of("agent", "customer"), "customer"))
            .isEqualTo(new Workspace.Resolution.Active(Workspace.CUSTOMER));
    }

    @Test
    void oneWorkspaceNeedsNoHeader() {
        assertThat(Workspace.resolve(List.of("staff", "ADMIN"), null))
            .isEqualTo(new Workspace.Resolution.Active(Workspace.STAFF));
    }

    @Test
    void aBlankHeaderCountsAsAbsent() {
        assertThat(Workspace.resolve(List.of("agent"), " "))
            .isEqualTo(new Workspace.Resolution.Active(Workspace.AGENT));
    }

    @Test
    void severalWorkspacesAndNoHeaderIsAmbiguous() {
        assertThat(Workspace.resolve(List.of("agent", "customer"), null))
            .isEqualTo(new Workspace.Resolution.Ambiguous(Set.of(Workspace.AGENT, Workspace.CUSTOMER)));
    }

    @Test
    void aWorkspaceTheTokenDoesNotHoldIsRefused() {
        assertThat(Workspace.resolve(List.of("customer"), "staff"))
            .isEqualTo(new Workspace.Resolution.NotHeld("staff"));
    }

    @Test
    void anUnknownHeaderValueIsRefused() {
        assertThat(Workspace.resolve(List.of("staff"), "ADMIN"))
            .isEqualTo(new Workspace.Resolution.NotHeld("ADMIN"));
    }

    @Test
    void noWorkspaceRoleAtAllIsNoWorkspace() {
        assertThat(Workspace.resolve(List.of("offline_access", "UNDERWRITER"), null))
            .isEqualTo(new Workspace.Resolution.NoWorkspace());
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run (from `backend/`): `JAVA_HOME=~/.jdks/jdk-21.0.12.1+1 ./mvnw -q test -Dtest=WorkspaceTest`
Expected: compilation failure, `cannot find symbol: class Workspace`.

- [ ] **Step 3: Implement `Workspace`**

```java
package tz.co.nlolo.lifeplatform.iam.infrastructure;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Which part of the platform a request acts in (2026-11-06, universal login). One Keycloak realm holds everyone; the
 * account's workspace roles say which consoles it may open, and the console names the one it is in with the
 * {@code X-Workspace} header. Exactly one workspace per request, so no request is ever both staff and customer.
 */
enum Workspace {
    STAFF("staff", "STAFF"),
    AGENT("agent", "AGENTS"),
    CUSTOMER("customer", "CUSTOMERS"),
    REGULATOR("regulator", "REGULATORS");

    static final String HEADER = "X-Workspace";

    private final String role;
    private final String marker;

    Workspace(String role, String marker) {
        this.role = role;
        this.marker = marker;
    }

    /** The Keycloak realm role, and the header value, that names this workspace. */
    String role() { return role; }

    /** The suffix of the {@code ROLE_REALM_*} authority every existing check reads. */
    String marker() { return marker; }

    /** Whether job roles (every realm role that is not a workspace role) count in this workspace. */
    boolean carriesJobRoles() { return this == STAFF || this == REGULATOR; }

    static Optional<Workspace> ofRole(String role) {
        for (Workspace w : values()) if (w.role.equals(role)) return Optional.of(w);
        return Optional.empty();
    }

    static boolean isWorkspaceRole(String role) { return ofRole(role).isPresent(); }

    sealed interface Resolution {
        record Active(Workspace workspace) implements Resolution {}
        record Ambiguous(Set<Workspace> held) implements Resolution {}
        record NotHeld(String requested) implements Resolution {}
        record NoWorkspace() implements Resolution {}
    }

    static Resolution resolve(Collection<String> roles, String header) {
        Set<Workspace> held = EnumSet.noneOf(Workspace.class);
        for (String role : roles) ofRole(role).ifPresent(held::add);
        if (held.isEmpty()) return new Resolution.NoWorkspace();
        if (header == null || header.isBlank()) {
            return held.size() == 1 ? new Resolution.Active(held.iterator().next()) : new Resolution.Ambiguous(held);
        }
        Optional<Workspace> requested = ofRole(header.trim());
        return requested.filter(held::contains)
            .<Resolution>map(Resolution.Active::new)
            .orElseGet(() -> new Resolution.NotHeld(header.trim()));
    }
}
```

- [ ] **Step 4: Run `WorkspaceTest`** — Expected: 7 tests PASS.

- [ ] **Step 5: Rewrite `SecurityConfigTest` for the new authorities rule (failing)**

```java
package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    private static Jwt token(String... roles) {
        return Jwt.withTokenValue("t").header("alg", "none")
            .claim("realm_access", Map.of("roles", List.of(roles)))
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    private static List<String> names(java.util.Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void staffGetsTheRealmMarkerAndJobRoles() {
        assertThat(names(SecurityConfig.authoritiesFor(token("staff", "ADMIN", "UNDERWRITER"), null)))
            .containsExactlyInAnyOrder("ROLE_REALM_STAFF", "ROLE_ADMIN", "ROLE_UNDERWRITER");
    }

    @Test
    void theWorkspaceRoleItselfIsNotAnAuthority() {
        assertThat(names(SecurityConfig.authoritiesFor(token("staff"), "staff"))).containsExactly("ROLE_REALM_STAFF");
    }

    @Test
    void agentAndCustomerGetOneMarkerEachAndNoJobRoles() {
        Jwt both = token("agent", "customer", "UNDERWRITER");
        assertThat(names(SecurityConfig.authoritiesFor(both, "agent"))).containsExactly("ROLE_REALM_AGENTS");
        assertThat(names(SecurityConfig.authoritiesFor(both, "customer"))).containsExactly("ROLE_REALM_CUSTOMERS");
    }

    @Test
    void regulatorKeepsItsOwnRole() {
        assertThat(names(SecurityConfig.authoritiesFor(token("regulator", "TIRA_READ_ONLY"), null)))
            .containsExactlyInAnyOrder("ROLE_REALM_REGULATORS", "ROLE_TIRA_READ_ONLY");
    }

    @Test
    void anUnresolvableWorkspaceGrantsNothing() {
        assertThat(SecurityConfig.authoritiesFor(token("agent", "customer"), null)).isEmpty();
        assertThat(SecurityConfig.authoritiesFor(token("customer"), "staff")).isEmpty();
        assertThat(SecurityConfig.authoritiesFor(token(), null)).isEmpty();
    }

    @Test
    void aTokenWithNoRealmAccessGrantsNothing() {
        Jwt bare = Jwt.withTokenValue("t").header("alg", "none")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(SecurityConfig.authoritiesFor(bare, "customer")).isEmpty();
    }
}
```

Run: `./mvnw -q test -Dtest=SecurityConfigTest` — Expected: compilation failure (`authoritiesFor(Jwt,String)` takes a marker today; semantics differ).

- [ ] **Step 6: Change `SecurityConfig`**

Replace the `issuerAuthenticationManagerResolver` bean, delete `authenticationManagerFor`, and replace `authoritiesFor`:

```java
    @Bean
    public AuthenticationManagerResolver<HttpServletRequest> issuerAuthenticationManagerResolver(
            @Value("${app.security.issuer}") String issuer) {
        JwtDecoder decoder = lazyDecoder(issuer);
        // Per request, because the authorities depend on the request's X-Workspace header. The decoder -- and with it
        // the issuer check that refuses any other realm's token -- is shared.
        return request -> {
            String workspace = request.getHeader(Workspace.HEADER);
            JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
            converter.setJwtGrantedAuthoritiesConverter(jwt -> authoritiesFor(jwt, workspace));
            JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
            provider.setJwtAuthenticationConverter(converter);
            return provider::authenticate;
        };
    }
```

```java
    /** The token's realm roles, as a list. */
    @SuppressWarnings("unchecked")
    static List<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) return List.of();
        return roles.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    /**
     * One {@code ROLE_REALM_<X>} for the request's workspace -- the names every {@code @PreAuthorize} already reads --
     * plus {@code ROLE_<role>} for each job role where the workspace carries them (staff, regulator). Nothing at all
     * when the workspace cannot be resolved; {@link WorkspaceFilter} then answers the request with why.
     */
    static Collection<GrantedAuthority> authoritiesFor(Jwt jwt, String workspaceHeader) {
        List<String> roles = realmRoles(jwt);
        if (!(Workspace.resolve(roles, workspaceHeader) instanceof Workspace.Resolution.Active active)) {
            return List.of();
        }
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_REALM_" + active.workspace().marker()));
        if (active.workspace().carriesJobRoles()) {
            for (String role : roles) {
                if (!Workspace.isWorkspaceRole(role)) authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }
        return authorities;
    }
```

Rewrite the class javadoc's first paragraph to: "JWT resource server for the one Keycloak realm, `lifeplatform` (2026-11-06; four realms before). Each request acts in one workspace -- see `Workspace` -- and gets that workspace's synthetic `ROLE_REALM_<X>` authority plus, in the staff and regulator workspaces, `ROLE_<Y>` per job role." Keep the lazy-decoder paragraph. Remove the now-unused `Map` import only if the compiler flags it (it is still used by `realmRoles`).

In `securityFilterChain`, register the workspace filter between bearer auth and the tenant filter:

```java
            .addFilterBefore(mobileMoneyHmacFilter, BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new WorkspaceFilter(objectMapper), BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new TenantContextFilter(objectMapper), WorkspaceFilter.class);
```

- [ ] **Step 7: Implement `WorkspaceFilter`**

```java
package tz.co.nlolo.lifeplatform.iam.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Answers a signed-in request whose workspace cannot be resolved (2026-11-06): several workspaces and no
 * {@code X-Workspace} (400), a workspace the account does not hold (403), or no workspace at all (403). Without this
 * such a request would carry no authorities and fail later with a bare 403 that says nothing. Runs before Spring MVC,
 * so it writes its own ProblemDetail with a traceId, as {@link TenantContextFilter} does.
 */
class WorkspaceFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    WorkspaceFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            Workspace.Resolution resolution =
                Workspace.resolve(SecurityConfig.realmRoles(jwt), request.getHeader(Workspace.HEADER));
            switch (resolution) {
                case Workspace.Resolution.Active ignored -> { }
                case Workspace.Resolution.Ambiguous a -> {
                    write(response, HttpStatus.BAD_REQUEST, "WORKSPACE_REQUIRED", "This account opens more than one"
                        + " workspace: send " + Workspace.HEADER + " naming one of " + a.held().stream()
                        .map(Workspace::role).sorted().collect(Collectors.joining(", ")) + ".");
                    return;
                }
                case Workspace.Resolution.NotHeld n -> {
                    write(response, HttpStatus.FORBIDDEN, "WORKSPACE_NOT_HELD",
                        "This account does not open the " + n.requested() + " workspace.");
                    return;
                }
                case Workspace.Resolution.NoWorkspace ignored -> {
                    write(response, HttpStatus.FORBIDDEN, "NO_WORKSPACE",
                        "This account has no access yet. Contact the insurer.");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", code);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        response.setStatus(status.value());
        response.setContentType("application/problem+json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
```

- [ ] **Step 8: Config, guard, compose**

`application.yml` — replace the `security.issuers` block:

```yaml
  security:
    # One realm for everyone (2026-11-06, universal login); the workspace comes from the X-Workspace header.
    issuer: ${KEYCLOAK_ISSUER:http://localhost:8081/realms/lifeplatform}
```

`ProductionSettingsGuard.java:32-33` — replace the four `app.security.issuers.*` entries with `"app.security.issuer"`. Update `ProductionSettingsGuardTest` wherever it sets or names the four issuer properties to set `app.security.issuer` instead (grep the test for `issuers`).

`backend/infra/docker-compose.yml:175-178` — replace the four `KEYCLOAK_ISSUER_*` lines with:

```yaml
      KEYCLOAK_ISSUER: http://keycloak:8080/realms/lifeplatform
```

- [ ] **Step 9: Run the touched tests**

Run: `./mvnw -q test -Dtest='WorkspaceTest,SecurityConfigTest,ProductionSettingsGuardTest'`
Expected: all PASS.

- [ ] **Step 10: Prove it against the live realm**

Restart the dev backend, then with the Task 1 token command store `T` for `staff.admin`:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $T" http://localhost:8080/me/access          # 200
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $T" -H "X-Workspace: customer" http://localhost:8080/me/access   # 403
curl -s -H "Authorization: Bearer $T" -H "X-Workspace: customer" http://localhost:8080/me/access   # body errorCode WORKSPACE_NOT_HELD
```

(Use whichever authenticated staff GET exists if `/me/access` is not it — `git grep -n "GetMapping(\"/me" backend/src/main` finds one.)

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/iam/infrastructure/Workspace.java backend/src/main/java/tz/co/nlolo/lifeplatform/iam/infrastructure/WorkspaceFilter.java backend/src/main/java/tz/co/nlolo/lifeplatform/iam/infrastructure/SecurityConfig.java backend/src/main/java/tz/co/nlolo/lifeplatform/ProductionSettingsGuard.java backend/src/main/resources/application.yml backend/infra/docker-compose.yml backend/src/test/java/tz/co/nlolo/lifeplatform/iam/infrastructure/WorkspaceTest.java backend/src/test/java/tz/co/nlolo/lifeplatform/iam/infrastructure/SecurityConfigTest.java backend/src/test/java/tz/co/nlolo/lifeplatform/ProductionSettingsGuardTest.java
git commit -m "feat(iam): one issuer; each request acts in one workspace (X-Workspace)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Logins land in the one realm, as workspace roles

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/distribution/infrastructure/KeycloakAgentLogins.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/omnichannel/infrastructure/KeycloakCustomerAccounts.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/iam/infrastructure/KeycloakStaffLogins.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/distribution/application/AgentLogins.java` (javadoc)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/omnichannel/application/CustomerAccounts.java` (javadoc)
- Modify: `backend/src/main/resources/application.yml:84-118` (three `keycloak:` blocks)
- Modify: `backend/infra/docker-compose.yml` (app env: admin secrets)

**Interfaces:**
- Consumes: realm `lifeplatform`, client `lifeplatform-admin` / `dev-secret-admin` (Task 1).
- Produces: unchanged port signatures. New semantics — `create(...)` creates **or joins** (same `party_id`) and grants the workspace role; `setEnabled(id, true|false)` on agent/customer ports **grants/revokes the workspace role** (revoke also signs the account out), never disables the Keycloak user, so closing an agent login leaves the same person's portal access working. Staff keep enable/disable (a staff account holds only `staff`, U10).

Deliberate, and kept simple: when an agent login joins a person's existing portal account (or the reverse), the services still issue the usual first password (link or texted temporary password), which replaces the person's current password; the same message tells them. Rare (one person both agent and policyholder) and secure; a "your account now also opens …" notice without a password change is a possible later refinement, not this step.

No unit test exists for these adapters (they are exercised live); the services' tests use fakes of the ports and keep passing because signatures do not change. Verification is the live check in Step 6.

- [ ] **Step 1: Config — one realm, one admin client**

In `application.yml`, in each of `app.staff-admin.keycloak`, `app.agent-logins.keycloak`, `app.portal.keycloak` set:

```yaml
      realm: lifeplatform
      client-id: lifeplatform-admin
      client-secret: ${KEYCLOAK_ADMIN_CLIENT_SECRET:dev-secret-admin}
```

Change `STAFF_CONSOLE_URL`/`AGENT_CONSOLE_URL`/`PORTAL_URL` defaults to `http://localhost:5173/` (the one sign-in). Replace the three comments' "of the staff/agents/customers realm" with "of the one realm (2026-11-06)". In `backend/infra/docker-compose.yml` app env, replace any `KEYCLOAK_PORTAL_ADMIN_SECRET`/`KEYCLOAK_STAFF_ADMIN_SECRET`/`KEYCLOAK_AGENTS_ADMIN_SECRET` lines with one `KEYCLOAK_ADMIN_CLIENT_SECRET: dev-secret-admin` (grep the file first; dev may rely on the defaults and have none).

- [ ] **Step 2: Workspace-role helpers in `KeycloakAgentLogins`**

Add the constant and helpers (all via endpoints `manage-users`/`view-users` allow; `/roles/{name}` would need `view-realm`, so the user's own available/assigned lists are used):

```java
    /** The workspace role this adapter gives and takes (2026-11-06, one realm). */
    private static final String WORKSPACE_ROLE = "agent";

    @SuppressWarnings("unchecked")
    private Map<String, Object> roleIn(String userId, String list) {
        List<Map<String, Object>> roles = http.get()
            .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm" + list, realm, userId)
            .header("Authorization", "Bearer " + token()).retrieve().body(List.class);
        return roles == null ? null
            : roles.stream().filter(r -> WORKSPACE_ROLE.equals(r.get("name"))).findFirst().orElse(null);
    }

    private void grantWorkspace(String userId) {
        Map<String, Object> role = roleIn(userId, "/available");
        if (role == null) return; // already held
        http.post().uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
            .header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON)
            .body(List.of(role)).retrieve().toBodilessEntity();
    }

    private void revokeWorkspace(String userId) {
        Map<String, Object> role = roleIn(userId, "");
        if (role == null) return; // not held
        http.method(HttpMethod.DELETE).uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
            .header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON)
            .body(List.of(role)).retrieve().toBodilessEntity();
    }
```

(Import `org.springframework.http.HttpMethod`.)

- [ ] **Step 3: `create` grants; `setEnabled` grants/revokes**

In `create`, wrap both return paths so the role is granted:

```java
            String path = location.getPath();
            String userId = path.substring(path.lastIndexOf('/') + 1);
            grantWorkspace(userId);
            return userId;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                String userId = existingLoginOf(username, partyId);
                grantWorkspace(userId);
                return userId;
            }
            throw unavailable(e);
        }
```

Wrap `grantWorkspace` failures the same way (`catch (RestClientResponseException e) { throw unavailable(e); }` around the grant calls). Update `existingLoginOf`'s javadoc to: "The same person's earlier login -- their own agent login, or their customer-portal login (one account, two workspaces, matched by party_id) -- is joined; anyone else's username, a staff login included (staff carry no party_id), is a refusal, never a takeover."

Replace `setEnabled`'s body:

```java
    @Override
    public void setEnabled(String userId, boolean enabled) {
        try {
            if (enabled) {
                grantWorkspace(userId);
            } else {
                // Take the agent workspace away, not the account: the same person's portal access stays.
                revokeWorkspace(userId);
                http.post().uri("/admin/realms/{realm}/users/{id}/logout", realm, userId)
                    .header("Authorization", "Bearer " + token()).retrieve().toBodilessEntity();
            }
        } catch (RestClientResponseException e) {
            throw unavailable(e);
        }
    }
```

`AgentLogins.java` javadoc: `create` → "Creates a login in the one realm, or joins this party's existing one, and gives it the agent workspace…"; `setEnabled` → "Opens or closes the agent workspace on the login; closing signs the person out everywhere but leaves their other workspaces."

- [ ] **Step 4: Same change in `KeycloakCustomerAccounts`** with `WORKSPACE_ROLE = "customer"` — the identical helper code from Step 2 (copy it; the modules may not share infrastructure classes — Modulith), the identical `create` wrapping from Step 3, and this `setEnabled`:

```java
    @Override
    public void setEnabled(String userId, boolean enabled) {
        try {
            if (enabled) {
                grantWorkspace(userId);
            } else {
                // Take the customer workspace away, not the account: the same person's agent login stays.
                revokeWorkspace(userId);
                http.post().uri("/admin/realms/{realm}/users/{id}/logout", realm, userId)
                    .header("Authorization", "Bearer " + token()).retrieve().toBodilessEntity();
            }
        } catch (RestClientResponseException e) {
            throw unavailable(e);
        }
    }
```

`CustomerAccounts.java` javadoc: say "the one realm (`lifeplatform`)" instead of "`customers` realm"; `create` joins the party's existing login; `setEnabled` opens/closes the customer workspace.

- [ ] **Step 5: `KeycloakStaffLogins.create` grants `staff`**

Copy the helpers with `WORKSPACE_ROLE = "staff"` (only `grantWorkspace` and `roleIn` are needed; `userId` is a `UUID` here, so pass `userId.toString()`). After the user is created:

```java
            UUID userId = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
            try {
                grantWorkspace(userId.toString());
            } catch (RestClientResponseException e) {
                throw refused(e);
            }
            return userId;
```

Leave the 409 refusal as is: a staff login never joins an existing account (U10). Leave `setEnabled` (enable/disable) as is.

- [ ] **Step 6: Live check** (dev backend restarted)

In the console as `staff.admin`: give portal access to a client with a phone number → in Keycloak admin (`http://localhost:8081/admin/master/console/#/lifeplatform/users`) the new user holds `customer`. Give that same client's party an agent login (onboard them as an agent first if needed) → **no new user**; the same user now holds `agent` and `customer`. Close the agent login → the user still exists, enabled, holds only `customer`. Add a staff login in Users & access → the user holds `staff`.

- [ ] **Step 7: Run the services' tests**

Run: `./mvnw -q test -Dtest='AgentLoginServiceTest,PortalAccessServiceTest,AccessAdministration*Test'` (use `git grep -l "class .*Test" backend/src/test | grep -i "agentlogin\|portalaccess\|accessadmin"` to get exact names).
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/distribution backend/src/main/java/tz/co/nlolo/lifeplatform/omnichannel/infrastructure/KeycloakCustomerAccounts.java backend/src/main/java/tz/co/nlolo/lifeplatform/omnichannel/application/CustomerAccounts.java backend/src/main/java/tz/co/nlolo/lifeplatform/iam/infrastructure/KeycloakStaffLogins.java backend/src/main/resources/application.yml backend/infra/docker-compose.yml
git commit -m "feat(logins): logins live in the one realm as workspace roles; agent and portal access share an account

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Seed and demo scripts on the one realm

**Files:**
- Modify: `backend/scripts/seed-lib.sh` (`token_for`, `realm_secret`)
- Modify: `backend/scripts/seed-dev-data.sh` (`realm_secret` calls, `write_party_id`)
- Modify: every other script that calls `token_for`, `realm_secret` or `/admin/realms/<old realm>` — find them with `git grep -n "token_for\|realm_secret\|admin/realms/" backend/scripts`

**Interfaces:**
- Produces: `token_for <ignored-old-realm> <username> <secret>` keeps its signature so the ~40 call sites need no edit; it always asks realm `lifeplatform`. `realm_secret <anything>` returns `lifeplatform-app`'s secret from `lifeplatform-realm.json`. `write_party_id <ignored-old-realm> <username> <partyId>` writes onto the one realm.

- [ ] **Step 1: `seed-lib.sh`**

`token_for`: change the URL to `"$KEYCLOAK/realms/lifeplatform/protocol/openid-connect/token"` and the comment to `# token_for <workspace (unused since 2026-11-06: one realm)> <username> <client-secret>`.
`realm_secret`: read `backend/keycloak/lifeplatform-realm.json` (keep the existing extraction logic; only the filename changes) and update its comment.

- [ ] **Step 2: `seed-dev-data.sh`** — in `write_party_id`, replace `$realm` in both `admin/realms/$realm/users` URLs with `lifeplatform` and leave the parameter for call-site compatibility; update the echo to `onto lifeplatform/$username`. Replace the three `X_SECRET=$(realm_secret …)` lines' comment with one noting all three are now the same client.

- [ ] **Step 3: Other scripts** — apply the same two substitutions anywhere the grep found a literal old realm in a URL.

- [ ] **Step 4: Re-seed and check**

Run the dev seed the way `backend/docs/09-local-development.md` describes (`bash backend/scripts/seed-dev-data.sh` from `backend/`).
Expected: completes; prints `wrote party_id=… onto lifeplatform/agent.senior` and the same for `customer.owner`.

- [ ] **Step 5: Commit**

```bash
git add backend/scripts
git commit -m "chore(seed): seed and demo scripts sign in to the one realm

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Console — one provider, workspace header

**Files:**
- Modify: `frontend/src/auth/realms.ts`
- Create: `frontend/src/auth/realms.test.ts`
- Modify: `frontend/src/lib/http.ts:41-43`
- Create: `frontend/src/auth/PlatformAuthProvider.tsx`; Delete: `frontend/src/auth/RealmAuthProvider.tsx`
- Modify: `frontend/src/auth/userStore.ts` (`createStateStore`)

**Interfaces:**
- Produces: `ISSUER: string`; `RealmConfig.workspaceRole: 'staff' | 'agent' | 'customer' | 'regulator'`; `workspacesOf(roles: string[]): Realm[]`; `realmForPath(pathname: string): Realm | null`; `workspaceHeaderFor(pathname: string): string | null`; `<PlatformAuthProvider>{children}</PlatformAuthProvider>`; `createStateStore(): StateStore`.

- [ ] **Step 1: Failing tests** — `frontend/src/auth/realms.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { ISSUER, realmForPath, workspaceHeaderFor, workspacesOf } from './realms';

describe('one realm', () => {
  it('has one issuer', () => {
    expect(ISSUER).toMatch(/\/realms\/lifeplatform$/);
  });
});

describe('workspacesOf', () => {
  it('reads the workspace roles, in console order, ignoring job roles', () => {
    expect(workspacesOf(['customer', 'ADMIN', 'agent', 'offline_access'])).toEqual(['agents', 'customers']);
  });

  it('is empty for an account with no workspace', () => {
    expect(workspacesOf(['UNDERWRITER'])).toEqual([]);
  });
});

describe('realmForPath / workspaceHeaderFor', () => {
  it('reads the workspace from the first path segment', () => {
    expect(realmForPath('/agents/book')).toBe('agents');
    expect(workspaceHeaderFor('/customers')).toBe('customer');
    expect(workspaceHeaderFor('/staff/policies/P-1')).toBe('staff');
  });

  it('has none outside a workspace', () => {
    expect(realmForPath('/')).toBeNull();
    expect(workspaceHeaderFor('/quote/abc')).toBeNull();
    expect(realmForPath('/staffing')).toBeNull();
  });
});
```

Run: `cd frontend && npx vitest run src/auth/realms.test.ts` — Expected: FAIL (exports missing).

- [ ] **Step 2: `realms.ts`**

Rewrite the file header comment to: "The four workspaces of the console and the routes they own (2026-11-06: one Keycloak realm, `lifeplatform`, for everyone; a workspace is a realm role, and the URL prefix says which workspace a page is in)." Keep `REALMS`, `Realm`, `REALM_CONFIG`, `CLIENT_ID`, `isRealm`. Add `workspaceRole` to `RealmConfig` and each entry (`staff`→`'staff'`, `agents`→`'agent'`, `customers`→`'customer'`, `regulators`→`'regulator'`). Replace `issuerFor` with:

```ts
/** The one realm (2026-11-06). */
export const ISSUER = `${KEYCLOAK_BASE}/realms/lifeplatform`;

/** The workspaces an account holds, in console order. Job roles (ADMIN, …) are not workspaces. */
export function workspacesOf(roles: readonly string[]): Realm[] {
  return REALMS.filter((realm) => roles.includes(REALM_CONFIG[realm].workspaceRole));
}

/** The workspace a path is in: its first segment, when that is a workspace's slug. */
export function realmForPath(pathname: string): Realm | null {
  const first = pathname.split('/')[1];
  return REALMS.find((realm) => REALM_CONFIG[realm].slug === first) ?? null;
}

/** The X-Workspace value for a path, or null outside a workspace. */
export function workspaceHeaderFor(pathname: string): string | null {
  const realm = realmForPath(pathname);
  return realm ? REALM_CONFIG[realm].workspaceRole : null;
}
```

Run the test — Expected: PASS. Then `git grep -n "issuerFor" frontend/src` — Expected: only `RealmAuthProvider.tsx` (deleted next step).

- [ ] **Step 3: `http.ts`** — in the request interceptor, after the Authorization line:

```ts
  // One realm for everyone (2026-11-06): the page's workspace says which part of the account this request acts in.
  const workspace = workspaceHeaderFor(window.location.pathname);
  if (token && workspace) config.headers['X-Workspace'] = workspace;
```

Import `workspaceHeaderFor` from `@/auth/realms` (realms.ts imports nothing from `lib/http`, so there is no cycle).

- [ ] **Step 4: `PlatformAuthProvider.tsx`** — copy `RealmAuthProvider.tsx`, then: rename the component `PlatformAuthProvider`, drop the `realm` prop and `config`, and set

```ts
  const settings = useMemo(
    () => ({
      authority: ISSUER,
      client_id: CLIENT_ID,
      // Everyone lands on "/", which routes by workspace (WorkspaceHome) or restores the page they asked for.
      redirect_uri: `${window.location.origin}/`,
      post_logout_redirect_uri: `${window.location.origin}/`,
      response_type: 'code',
      scope: 'openid profile email',
      userStore: createUserStore(),
      stateStore: createStateStore(),
      automaticSilentRenew: true,
      loadUserInfo: false,
      onSigninCallback: () => {
        window.history.replaceState({}, '', window.location.pathname);
      },
    }),
    [],
  );
```

Keep `HttpAuthBridge` and the comments that explain `onSigninCallback`, adjusted to "the one provider, mounted once at the root". `git rm frontend/src/auth/RealmAuthProvider.tsx`.

- [ ] **Step 5: `userStore.ts`** — `export function createStateStore(): StateStore` with prefix `'lifeplatform.'`.

- [ ] **Step 6: Typecheck** — `cd frontend && npx tsc -b --noEmit` (or the project's `npm run typecheck` if defined). Expected: errors only in `App.tsx`/`RequireAuth.tsx`/`RealmPicker.tsx` (fixed in Task 6). Do not commit yet; Task 6 completes the compile.

---

### Task 6: Console — `/` signs everyone in; workspace home, guard, switcher

**Files:**
- Modify: `frontend/src/auth/RequireAuth.tsx`
- Create: `frontend/src/auth/RequireWorkspace.tsx`
- Create: `frontend/src/features/WorkspaceHome.tsx`, `frontend/src/features/WorkspaceHome.test.tsx`
- Delete: `frontend/src/features/RealmPicker.tsx`
- Modify: `frontend/src/entryPath.ts`, `frontend/src/entryPath.test.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/components/AppShell.tsx` (`UserBlock`, ~line 399-465)
- Modify: `deploy/web/Dockerfile:19` (drop `VITE_REALM_PICKER=false`)

**Interfaces:**
- Consumes: Task 5 exports; `SCREENS` from `@/screens`; `readIdentity` from `@/auth/claims`.
- Produces: `<RequireAuth>{children}</RequireAuth>` (no `realm` prop); `<RequireWorkspace realm={realm}>`; `<WorkspaceHome />`; `availableWorkspaces(roles: readonly string[]): Realm[]` (exported from `WorkspaceHome.tsx`).

- [ ] **Step 1: Failing test** — `frontend/src/features/WorkspaceHome.test.tsx`:

```tsx
import { describe, expect, it } from 'vitest';
import { availableWorkspaces } from './WorkspaceHome';

describe('availableWorkspaces', () => {
  it('offers only workspaces the account holds AND the console has screens for', () => {
    // regulators has no screens yet (screens.tsx), so a regulator is offered nothing
    expect(availableWorkspaces(['regulator', 'TIRA_READ_ONLY'])).toEqual([]);
  });

  it('offers agent and policyholder to an account holding both', () => {
    expect(availableWorkspaces(['agent', 'customer'])).toEqual(['agents', 'customers']);
  });

  it('offers staff alone to a staff account, whatever its job roles', () => {
    expect(availableWorkspaces(['staff', 'ADMIN', 'UNDERWRITER'])).toEqual(['staff']);
  });
});
```

Run: `npx vitest run src/features/WorkspaceHome.test.tsx` — Expected: FAIL (module missing).

- [ ] **Step 2: `WorkspaceHome.tsx`** — built from `RealmPicker.tsx`'s markup (same card list, same classes, so it looks like the picker it replaces):

```tsx
import { ArrowRight } from 'lucide-react';
import { useAuth } from 'react-oidc-context';
import { Link, Navigate } from 'react-router-dom';
import { readIdentity } from '@/auth/claims';
import { REALM_CONFIG, workspacesOf, type Realm } from '@/auth/realms';
import { Button } from '@/components/ui/button';
import { SCREENS } from '@/screens';

/** Workspaces this account holds that the console can open (one with no screens yet is not offered). */
export function availableWorkspaces(roles: readonly string[]): Realm[] {
  return workspacesOf(roles).filter((realm) => SCREENS[realm].length > 0);
}

/**
 * Where everyone lands after signing in (2026-11-06, universal login; replaces the realm picker). One workspace opens
 * at once; several are offered ("Continue as …"); none is said plainly rather than shown as an empty console.
 */
export function WorkspaceHome() {
  const auth = useAuth();
  const workspaces = availableWorkspaces(readIdentity(auth.user?.access_token).roles);

  if (workspaces.length === 1) return <Navigate to={`/${REALM_CONFIG[workspaces[0]].slug}`} replace />;

  return (
    <div className="grid min-h-full place-items-center px-6 py-16">
      <div className="w-full max-w-md">
        <h1 className="text-headline">Life Platform</h1>
        {workspaces.length === 0 ? (
          <>
            <p className="mt-1 text-sm text-muted-foreground">
              This account has no access yet. Contact the insurer.
            </p>
            <Button className="mt-6" variant="outline" onClick={() => void auth.signoutRedirect()}>
              Sign out
            </Button>
          </>
        ) : (
          <>
            <p className="mt-1 text-sm text-muted-foreground">Choose how you are continuing.</p>
            <ul className="mt-6 space-y-2">
              {workspaces.map((realm) => {
                const config = REALM_CONFIG[realm];
                return (
                  <li key={realm}>
                    <Link
                      to={`/${config.slug}`}
                      className="flex items-center justify-between gap-4 rounded-xl border border-border bg-surface shadow-card px-4 py-3 transition-colors hover:border-border-strong hover:bg-hover"
                    >
                      <span className="min-w-0">
                        <span className="block text-sm font-medium">Continue as {config.label}</span>
                        <span className="block text-xs text-muted-foreground">{config.description}</span>
                      </span>
                      <ArrowRight className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                    </Link>
                  </li>
                );
              })}
            </ul>
          </>
        )}
      </div>
    </div>
  );
}
```

Run the test — Expected: PASS. `git rm frontend/src/features/RealmPicker.tsx`.

- [ ] **Step 3: `RequireWorkspace.tsx`**

```tsx
import type { ReactNode } from 'react';
import { useAuth } from 'react-oidc-context';
import { Navigate } from 'react-router-dom';
import { readIdentity } from './claims';
import { workspacesOf, type Realm } from './realms';

/** A workspace's pages, for accounts that hold it. Anyone else goes back to "/", which offers what they do hold. */
export function RequireWorkspace({ realm, children }: { realm: Realm; children: ReactNode }) {
  const auth = useAuth();
  const held = workspacesOf(readIdentity(auth.user?.access_token).roles);
  return held.includes(realm) ? <>{children}</> : <Navigate to="/" replace />;
}
```

- [ ] **Step 4: `RequireAuth.tsx`** — drop the `realm` prop from `RequireAuth` and `RestoreLocation`. In `RestoreLocation` the fallback becomes `readReturnTo(auth.user) ?? '/'`. In the error block replace the realm sentence with: `Check that Keycloak is running and that the <code>lifeplatform-spa</code> client exists in the <code>lifeplatform</code> realm.` The splash becomes `<SessionSplash label="Signing you in" />`. Remove the `REALM_CONFIG`/`Realm` imports.

- [ ] **Step 5: `entryPath.ts`** — delete `SHOW_REALM_PICKER` and `entryPath` and their header comment; keep `hasRealmFallback` and its comment. In `entryPath.test.ts` delete the `describe('entryPath', …)` block and its import.

- [ ] **Step 6: `App.tsx`**

- Imports: drop `RealmAuthProvider`, `RealmPicker`, `entryPath`, `SHOW_REALM_PICKER`, `Navigate` stays; add `PlatformAuthProvider`, `RequireWorkspace`, `WorkspaceHome`.
- Delete the `ENTRY` constant and its comment.
- Replace the `/` route with:

```tsx
        {/* One sign-in for everyone (2026-11-06): "/" signs in, then opens the account's workspace or offers a choice. */}
        <Route path="/" element={<RequireAuth><WorkspaceHome /></RequireAuth>} />
```

- `RealmSubtree`:

```tsx
function RealmSubtree({ realm }: { realm: Realm }) {
  return (
    <RequireAuth>
      <RequireWorkspace realm={realm}>
        {realm === 'staff' ? (
          <StaffAccessGate>
            <AppShell realm={realm}>
              <Outlet />
            </AppShell>
          </StaffAccessGate>
        ) : (
          <AppShell realm={realm}>
            <Outlet />
          </AppShell>
        )}
      </RequireWorkspace>
    </RequireAuth>
  );
}
```

- `App`:

```tsx
export function App() {
  return (
    <PlatformAuthProvider>
      <RouterProvider router={router} />
    </PlatformAuthProvider>
  );
}
```

- Rewrite the file's top comment: the provider is mounted once; the URL prefix now names a *workspace*, not a realm.

- [ ] **Step 7: Switcher in `AppShell`'s `UserBlock`** — above the theme/sign-out row:

```tsx
      {availableWorkspaces(identity.roles)
        .filter((other) => other !== realm)
        .map((other) => (
          <Button key={other} asChild size="sm" variant="ghost" className="mt-1 w-full justify-start">
            <Link to={`/${REALM_CONFIG[other].slug}`}>Switch to {REALM_CONFIG[other].label}</Link>
          </Button>
        ))}
```

Import `availableWorkspaces` from `@/features/WorkspaceHome`, `REALM_CONFIG` from `@/auth/realms`, `Link` from `react-router-dom` (check which are already imported). If `Button` has no `asChild`, use a `Link` with the same classes as the ghost button (`git grep -n "asChild" frontend/src/components/ui/button.tsx`).

- [ ] **Step 8: Dockerfile** — delete `VITE_REALM_PICKER=false` from `deploy/web/Dockerfile` (fix the line continuation on the line before). `git grep -n VITE_REALM_PICKER` — Expected: nothing.

- [ ] **Step 9: Typecheck, lint, full vitest** (vitest alone, nothing else running)

```bash
cd frontend && npx tsc -b --noEmit && npm run lint && npx vitest run
```

Expected: clean, all tests PASS (designGuards included — memory: full vitest before a merge).

- [ ] **Step 10: Browser check** (dev stack + `npm run dev`)

1. Open `http://localhost:5173/` → Keycloak's green login page, realm `lifeplatform`. Sign in `staff.admin` / `devpassword` → Staff Dashboard. Sign out → back to the login page.
2. Sign in `agent.senior` → agent console. Sign in `customer.owner` → customer portal.
3. Deep link `http://localhost:5173/staff/policies` signed out → login → lands on `/staff/policies`.
4. As `customer.owner`, visit `/staff` → bounced to `/` → customer portal.
5. In Keycloak admin give `customer.owner` the `agent` role; sign in again → "Continue as Agent / Policyholder"; pick Agent; the account card shows "Switch to Policyholder"; network tab shows `X-Workspace: agent` then `customer` after switching. Remove the role again afterwards.
6. Give a seed user an email in Keycloak admin and sign in with the email → works.

- [ ] **Step 11: Commit**

```bash
git add frontend/src deploy/web/Dockerfile
git commit -m "feat(console): one sign-in at /, workspace home and switcher, X-Workspace on every request

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: E2E on the one realm

**Files:**
- Modify: `frontend/e2e/auth.setup.ts`, `auth-admin.setup.ts`, `auth-assessor.setup.ts`, `auth-finance-approver.setup.ts`, `auth-finance.setup.ts`, `auth-manager.setup.ts`, `auth-senior.setup.ts`, `auth-service.setup.ts`, `agentSession.ts`
- Modify: `frontend/e2e/creditLife.ts:39-60`, `frontend/e2e/staff-client-kyc-lifecycle.spec.ts:16-50`

**Interfaces:**
- Consumes: realm `lifeplatform`, `lifeplatform-app` secret in `backend/keycloak/lifeplatform-realm.json`.

- [ ] **Step 1: Setups** — in each `*.setup.ts` and `agentSession.ts` replace the `waitForURL` regex `/\/realms\/(staff|agents)\/protocol\/openid-connect\/auth/` with `/\/realms\/lifeplatform\/protocol\/openid-connect\/auth/`. The `page.goto('/staff')` / `('/agents')` lines stay (the workspace path triggers sign-in and is restored after). Update the comments that say "the REAL Keycloak `staff` realm" to "the real Keycloak realm (`lifeplatform`)".

- [ ] **Step 2: `creditLife.ts`** — read `../backend/keycloak/lifeplatform-realm.json`, post to `${KEYCLOAK}/realms/lifeplatform/protocol/openid-connect/token`; update the error text's filename.

- [ ] **Step 3: `staff-client-kyc-lifecycle.spec.ts`** — `clientSecret()` takes no argument and reads `lifeplatform-realm.json`; `token(http, username)` posts to the lifeplatform token endpoint; the two calls become `token(http, 'agent.senior')` and `token(http, 'staff.admin')`.

- [ ] **Step 3b: Seed accounts must not be joined by test agents** — memory says the e2e agents reuse party "Amina Owner". Check whether `customer.owner`'s `party_id` (after seeding) is that party: `git grep -n "Amina" frontend/e2e backend/scripts`. If it is, an agent-login e2e would now join `customer.owner`'s account and reset its password. Fix it in the e2e fixture by giving those test agents their own throwaway parties (follow the fixture that creates them), not by changing the join rule.

- [ ] **Step 4: Stale saved state** — `rm -f frontend/e2e/.auth/*.json` (they hold the old realms' SSO cookies; memory: Playwright --no-deps stale auth).

- [ ] **Step 5: Run the setups and one spec per role** (dev backend + Vite running, nothing else heavy)

```bash
cd frontend && npx playwright test staff-dashboard.spec.ts agents-my-book.spec.ts --workers=2
```

Expected: setups pass, both specs PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/e2e
git commit -m "test(e2e): sign in through the one realm

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Production realm, deploy scripts, docs

**Files:**
- Modify: `deploy/keycloak/build-realms.mjs`, `deploy/keycloak/build-realms.test.mjs`
- Modify: `deploy/server/compose.yml` (keycloak-config-cli env ~115-117; backend env ~143-152)
- Modify: `deploy/server/smoke.sh:26-28`
- Modify: `deploy/server/add-user.sh`
- Modify: `deploy/README.md`, server `.env` example if present (`git ls-files deploy | grep -i env`)
- Modify: `backend/docs/04-api-contracts.md` §3, `backend/docs/07-infrastructure-architecture.md`, `backend/docs/09-local-development.md`, `backend/docs/10-frontend-development.md`, `backend/keycloak/README.md`, `backend/keycloak/themes/lifeplatform/README.md` (realm mentions only)

**Interfaces:**
- Produces: `buildRealm(realm)` (one argument now) and `buildAll(outDir)` writing `lifeplatform.json`; server `.env` keys `ADMIN_CLIENT_SECRET` (replaces `PORTAL_ADMIN_CLIENT_SECRET`, `STAFF_ADMIN_CLIENT_SECRET`, `AGENTS_ADMIN_CLIENT_SECRET`).

- [ ] **Step 1: Update `build-realms.test.mjs` first** (read it; keep each existing assertion that still makes sense) so it asserts on the one realm:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { buildRealm } from './build-realms.mjs';

const dev = JSON.parse(readFileSync(new URL('../../backend/keycloak/lifeplatform-realm.json', import.meta.url), 'utf8'));
const prod = buildRealm(dev);

test('one realm named lifeplatform', () => assert.equal(prod.realm, 'lifeplatform'));
test('only service-account users survive', () =>
  assert.deepEqual(prod.users.map((u) => u.username), ['service-account-lifeplatform-admin']));
test('no password-grant client', () => assert.ok(!prod.clients.some((c) => c.clientId === 'lifeplatform-app')));
test('the console client points at APP_DOMAIN only', () => {
  const spa = prod.clients.find((c) => c.clientId === 'lifeplatform-spa');
  assert.deepEqual(spa.redirectUris, ['https://$(env:APP_DOMAIN)/*']);
});
test('the admin secret comes from the server env', () =>
  assert.equal(prod.clients.find((c) => c.clientId === 'lifeplatform-admin').secret, '$(env:ADMIN_CLIENT_SECRET)'));
test('the four workspace roles exist', () =>
  ['staff', 'agent', 'customer', 'regulator'].forEach((r) => assert.ok(prod.roles.realm.some((x) => x.name === r))));
test('lockout and policy', () => {
  assert.equal(prod.bruteForceProtected, true);
  assert.equal(prod.passwordPolicy, 'length(8) and notUsername(undefined) and notEmail(undefined) and passwordHistory(3)');
});
```

Run: `node --test deploy/keycloak/build-realms.test.mjs` — Expected: FAIL.

- [ ] **Step 2: `build-realms.mjs`** — `REALMS` → read only `lifeplatform-realm.json`; `buildRealm(realm)` drops the `staffSpa` parameter and the "regulators never had the console's client" branch (the one realm has it); the secret loop sets `lifeplatform-admin` → `'$(env:ADMIN_CLIENT_SECRET)'` and drops the other two; `buildAll` writes `lifeplatform.json`; the CLI log says `Wrote the production realm to …`. Update the header comment (one realm since 2026-11-06). Run the test — Expected: PASS.

- [ ] **Step 3: `deploy/server/compose.yml`** — config-cli env: one `ADMIN_CLIENT_SECRET: ${ADMIN_CLIENT_SECRET}`; backend env: `KEYCLOAK_ISSUER: https://${AUTH_DOMAIN}/realms/lifeplatform` replacing the four, and `KEYCLOAK_ADMIN_CLIENT_SECRET: ${ADMIN_CLIENT_SECRET}` replacing the three; set the backend's `STAFF_CONSOLE_URL`/`AGENT_CONSOLE_URL`/`PORTAL_URL` (if present) to `https://${APP_DOMAIN}/`. Update the `.env` example the same way and `deploy/server/remote-release.sh`/`bootstrap-server.sh` if they generate those secrets (`git grep -n "ADMIN_CLIENT_SECRET" deploy`).

- [ ] **Step 4: `smoke.sh`** — check `https://$AUTH_DOMAIN/realms/lifeplatform/.well-known/openid-configuration` reports issuer `https://$AUTH_DOMAIN/realms/lifeplatform`; fix the failure text.

- [ ] **Step 5: `add-user.sh`** — first argument becomes the workspace: `staff|agent|regulator` (customers still come from console invites). Users are created in realm `lifeplatform`; after creation add the workspace role with `kc add-roles -r lifeplatform --uusername "$USERNAME" --rolename "$WORKSPACE"`; keep the staff `--home-branch` and agent `--party-id` requirements; keep job-role assignment for staff. Update the usage line and the README example.

- [ ] **Step 6: Docs** — rewrite only the realm-specific passages:
  - `04-api-contracts.md` §3: one realm `lifeplatform`; workspace roles; the `X-Workspace` rule (the five cases from Global Constraints) and the `WORKSPACE_REQUIRED` / `WORKSPACE_NOT_HELD` / `NO_WORKSPACE` error codes; U10.
  - `07-infrastructure-architecture.md`: "four realms" → one realm; production issuer.
  - `09-local-development.md`: realm import is `lifeplatform-realm.json`; seed users and `devpassword`; how to delete the four old realms from an existing dev Keycloak (Task 1 Step 6 commands); the apply-* scripts are gone.
  - `10-frontend-development.md`: one provider at the root, `/` sign-in, workspace prefixes, `X-Workspace` from the path, `RequireWorkspace`.
  - `backend/keycloak/README.md`: one realm file; roles; clients.
  - `deploy/README.md`: `ADMIN_CLIENT_SECRET`, one realm, `add-user.sh <staff|agent|regulator>`, and "`/` is the sign-in for everyone" replacing D8's "opens the customer portal".

- [ ] **Step 7: Leftover grep**

```bash
git grep -nE "realms/(staff|agents|customers|regulators)|(staff|agents|customers|regulators)-realm\.json|KEYCLOAK_ISSUER_|issuers\.|STAFF_ADMIN_CLIENT_SECRET|AGENTS_ADMIN_CLIENT_SECRET|PORTAL_ADMIN_CLIENT_SECRET|RealmPicker|SHOW_REALM_PICKER" -- ':!backend/docs/superpowers' ':!*.pdf' ':!backend/api/openapi'
```

Expected: no hits. Then check `backend/api/openapi/*.yaml` hits by eye: they describe security schemes per realm; change their issuer URLs to `/realms/lifeplatform` and add `X-Workspace` as a documented header in `openapi-common.yaml`, then regenerate the frontend types the way `frontend/package.json` defines (`npm run gen:api` or equivalent) and confirm `git diff --stat frontend/src/api` shows only expected changes.

- [ ] **Step 8: Commit**

```bash
git add deploy backend/docs backend/keycloak/README.md backend/keycloak/themes/lifeplatform/README.md backend/api/openapi frontend/src/api
git commit -m "chore(deploy,docs): one production realm, one admin secret, docs for the one sign-in

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Merge gate

- [ ] **Step 1: Backend suite** — stop the dev backend (memory: dev backend breaks `clean test`), then from `backend/`:
  `JAVA_HOME=~/.jdks/jdk-21.0.12.1+1 ./mvnw clean test` in the background; nothing else heavy meanwhile. Compare the class count with the previous run (memory: vacuous verification traps). Restart the dev backend after.
- [ ] **Step 2: Full vitest** — `cd frontend && npx vitest run`.
- [ ] **Step 3: Full e2e** — `cd frontend && npx playwright test --workers=2` (memory: low RAM). Treat every failure as real (memory: "pre-existing" failures are real); re-run a budget-looking failure alone before calling it flaky.
- [ ] **Step 4: Whole-branch review** — read `git diff main...universal-login` end to end looking for seams: any `setEnabled(false)` on an agent/customer port that still disables a user; any request path the console makes outside a workspace prefix while signed in (it would carry no `X-Workspace` and, for a two-workspace account, get 400); any script still naming an old realm.
- [ ] **Step 5: Merge** — `git checkout main && git merge --no-ff universal-login` and push (memory: GitHub remote) only after the user approves.
