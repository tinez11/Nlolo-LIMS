# Research 05: can Authentik issue the tokens universal login needs?

Ticket: [05-tokens-and-universal-login](../issues/05-tokens-and-universal-login.md). Researched 2026-11-19.

**Version researched:** authentik **2026.8.5**, the latest stable release (published 2026-10-21; `GET https://api.github.com/repos/goauthentik/authentik/releases/latest` → `version/2026.8.5`). Source citations below are to that tag: `https://github.com/goauthentik/authentik/blob/version/2026.8.5/<path>#L<n>` (written as `<path>:<lines>` for brevity). Docs citations are to docs.goauthentik.io, whose version selector showed 2026.8.

Today's side: Keycloak **26.0.7** (`deploy/auth/Dockerfile:6`), Spring Boot 3.3.5 → Spring Security **6.3.4** / Spring Framework 6.1.14, `oidc-client-ts` **3.5.0** under `react-oidc-context`.

---

## 1. What the platform consumes from the token today

All from the **access token** (the SPA sets `loadUserInfo: false` and decodes `access_token`, `frontend/src/auth/PlatformAuthProvider.tsx:28-31`, `HttpAuthBridge`).

| Claim | Consumer | What breaks if it is missing |
|---|---|---|
| `realm_access.roles` (list) | `SecurityConfig.realmRoles` → `authoritiesFor` → `Workspace.resolve` (backend); `claims.ts decodeIdentity` → `workspacesOf` (frontend) | Every request: no `ROLE_REALM_*`, `WorkspaceFilter` refuses. Holds both the workspace roles (`staff`, `agent`, `customer`, `regulator`) and the job roles (`ADMIN`, `UNDERWRITER`, ...) |
| `tenant_id` (UUID string) | `TenantContextFilter` (403 `TENANT_CLAIM_MISSING` if absent/malformed); `claims.ts` | Every authenticated request |
| `party_id` (string) | 27 backend files via `jwt.getClaimAsString("party_id")` (customer/agent ownership checks, e.g. `ActionsController:35`); `claims.ts` | Customer and agent object-level checks |
| `home_branch` | `UnderwritingController:198` (fallback branch); written as a user attribute by `KeycloakStaffLogins:81` | Underwriting case branch default (staff only). **Not named in the ticket, but consumed.** |
| `sub` | ~every controller as the actor id (`jwt.getSubject()`), separation of duties; `AccessExpressions.userIdOf` parses it as a **UUID** (falls back to a name-based UUID otherwise, `AccessExpressions.java:~100`) | Audit trails, maker-checker; staff identity mapping |
| `name`, `preferred_username` | `TokenNames.displayName`, `CollectionActionController:55`, `ReportController:78-101`, `AccessExpressions:92` and others; `claims.ts` | Display labels only (fall back to each other) |
| `email` | `AccessExpressions:92` (StaffIdentity), `claims.ts` | Display fallback only |
| `iss`, `exp`, signature | `NimbusJwtDecoder.withIssuerLocation(issuer)` (`SecurityConfig:72-90`) | Everything |

Keycloak config that produces these: `backend/keycloak/lifeplatform-realm.json` client `lifeplatform-spa`: `publicClient: true`, `pkce.code.challenge.method: S256`, redirect `http://localhost:5173/*` (production: `https://$(env:APP_DOMAIN)/*`, `deploy/keycloak/build-realms.mjs:39-41`), and three `oidc-usermodel-attribute-mapper`s (`tenant_id`, `party_id`, `home_branch`, each to ID + access token + userinfo). Roles use Keycloak's built-in `realm_access` mapper. The realm sets no lifespans or signature algorithm, so Keycloak's import defaults apply (section 6).

SPA request: `response_type: 'code'`, `scope: 'openid profile email'`, `redirect_uri` and `post_logout_redirect_uri` = `${origin}/` (one URI for all four consoles; the workspace is the URL prefix), `automaticSilentRenew: true` (`PlatformAuthProvider.tsx:18-28`).

---

## 2. Can one OAuth2/OIDC provider + application serve all four consoles as a public PKCE client?

**Yes.**

- A provider has `client_type` `confidential` (default) or `public` (`authentik/providers/oauth2/models.py:80-85, 222-231`). Docs: "Public clients, which cannot securely store a client secret, should use the authorization code flow with PKCE." ([OAuth 2.0 provider](https://docs.goauthentik.io/add-secure-apps/providers/oauth2/)).
- Redirect URIs are a **list** of `{matching_mode: strict|regex, url, redirect_uri_type: authorization|logout}` (`models.py:125-148, 245-248, 380-417`). Authorization requests are checked against every authorization-type entry, strict equality or `re.fullmatch` (`views/authorize.py:245-277`); the token endpoint repeats the check (`token/base.py:179-219`). Docs: "an authentik administrator can use regular expressions instead of exact redirect URIs" ([OAuth 2.0 provider, Redirect URIs](https://docs.goauthentik.io/add-secure-apps/providers/oauth2/)).
- Because the console already uses one `redirect_uri` (`${origin}/`) for all four workspaces, **one strict authorization entry per origin** is enough (dev `http://localhost:5173/`, prod `https://<APP_DOMAIN>/`). Keycloak's `/*` wildcard has no direct equivalent; it is either strict (which is all the SPA needs) or a regex.
- **Post-logout redirect** needs its own entry with `redirect_uri_type: logout`. With one configured, `post_logout_redirect_uri` is only honoured when `id_token_hint` is also sent (`views/end_session.py:93-111`). oidc-client-ts sends `id_token_hint` on `signoutRedirect` by default (UNCONFIRMED for 3.5.0; not checked).
- **PKCE: supported but not enforced.** The authorize endpoint stores `code_challenge`/`code_challenge_method` if sent (`views/authorize.py:385-420`) and the token endpoint verifies it if the code had one, and refuses a verifier without a challenge (`token/authorization_code.py:39-55`). But a public client may omit PKCE, and `plain` is accepted (`views/authorize.py:147, 387-390`; discovery advertises `["plain","S256"]`, `views/provider.py:126`). Keycloak today **forces S256** (`pkce.code.challenge.method: S256`). oidc-client-ts always sends S256, so behaviour is unchanged for the SPA. To keep the server-side guarantee, bind an expression policy to the application that refuses unless `request.context["oauth_code_challenge_method"] == "S256"`: the authorize view puts exactly that key in the policy context (`views/authorize.py:476-483`).
- Confidential-only checks (client secret) are skipped for public clients at the token endpoint (`token/base.py:82-96`); the public client can call the revocation endpoint without a secret (`views/token_revoke.py:31-45`).

---

## 3. Can mappings put `roles`, `tenant_id`, `party_id` into the **access token**?

**Yes, with one condition: the provider's "Include claims in id_token" must stay ON (its default).**

How the access token is built (source):

1. On the code exchange and on every refresh, authentik builds an `IDToken` for the access token and encodes it as the access token: `access_token.id_token = IDToken.new(...)` (`views/token.py:130-137` code grant, `:196-202` refresh grant), and the setter runs `self.token = value.to_access_token(self.provider, self)` (`models.py:655-658`).
2. `to_access_token` = `to_dict()` of the ID token (standard claims **plus `self.claims`**) + `azp`, a random `uid`, and `scope` (`id_token.py:167-175`). So the access token is a JWT that is a superset of the ID token.
3. `IDToken.claims` is filled from the scope mappings **only when** `provider.include_claims_in_id_token` is true (`id_token.py:123-129`). That field defaults to `True` (`models.py:263-270`).
4. The scope mappings evaluated are those attached to the provider **and** requested in `scope` (`views/userinfo.py:67-104`); the results are merged dict-wise.

Docs agree: scope-mapping claims "are available from the `UserInfo` endpoint"; "authentik includes them in the ID token and access token only when Include claims in id_token is enabled on the provider." ([Provider property mappings, Scope mappings with OAuth2](https://docs.goauthentik.io/add-secure-apps/providers/property-mappings/)). A mapping expression "must return a dictionary" (same page).

Consequences for the platform:

- **Turning off "Include claims in id_token" silently strips `roles`/`tenant_id`/`party_id` from the access token**, so every request 403s. The blueprint must set `include_claims_in_id_token: true` explicitly, and a backend test should pin it.
- Claims must be produced by a **scope** the SPA requests. Simplest: one custom scope mapping, e.g. scope name `lifeplatform`, added to the SPA's `scope` (`'openid profile lifeplatform offline_access'`, see section 6). Scopes not attached to the provider are silently dropped from the request (`views/authorize.py:280-300`, `token/base.py:105-118`), so a typo shows up as missing claims, not as an error.
- Expression variables: `request` (with `request.user`) and `user` ([Expression reference](https://docs.goauthentik.io/add-secure-apps/providers/property-mappings/expression/)); the OAuth2 caller passes `user`, `request`, `provider`, `token` (`views/userinfo.py:79-84`). User attributes are a JSON dict (`authentik/core/models.py:134-137`), so values are plain strings (Keycloak stored them as one-element lists).
- **Groups for `roles`:** `user.all_groups()` returns direct groups plus ancestors (`authentik/core/models.py:416-418`); `user.groups.all()` returns direct ones only. Sketch:

  ```python
  # Scope mapping, scope_name: lifeplatform
  return delete_none_values({   # same helper the default profile mapping uses
      "roles": [g.name for g in request.user.all_groups()],
      "tenant_id": request.user.attributes.get("tenant_id"),
      "party_id": request.user.attributes.get("party_id"),
      "home_branch": request.user.attributes.get("home_branch"),
  })
  ```
  `delete_none_values` appears in the shipped `profile` mapping (`blueprints/system/providers-oauth2.yaml`, scope-profile); I did not separately confirm it is in the expression globals beyond that use.

- **Name clash to avoid:** the shipped `entitlements` scope mapping also returns a `roles` key (`blueprints/system/providers-oauth2.yaml`, scope-entitlements). Lists from several mappings are merged ([Claims returned by several scope mappings](https://docs.goauthentik.io/add-secure-apps/providers/property-mappings/)), so **do not attach the `entitlements` mapping** to this provider, or app entitlements would leak into `roles`.
- **Noise from defaults:** the shipped `profile` mapping emits `name`, `given_name`, `family_name`, `preferred_username`, `nickname`, **`groups`** (every group name, again) and `picture` (same file, scope-profile). `name`/`preferred_username` are what the platform reads. `groups` duplicates `roles`: harmless, but it doubles the token size. A custom `profile` mapping can leave it out. The shipped `email` mapping always returns `email` (possibly `""`) and `email_verified: false` (scope-email). Email is never a channel here, so the SPA can stop asking for `email`. The claim is then absent, which `claims.ts` and `AccessExpressions` already tolerate.
- **`sub` must be switched to UUID.** `sub_mode` defaults to `hashed_user_id` (`models.py:307-314`), which is `sha256(f"{id}-{install id}")` hex (`authentik/core/models.py:607-609`), not a UUID. `AccessExpressions.userIdOf` would then fall back to a name-based UUID. That works, but it is not the identity the admin-API adapters would store. Set `sub_mode: user_uuid` → `str(user.uuid)` (`id_token.py:139-153`; `SubModes` in `authentik/common/oauth/constants.py:89-101`; `User.uuid` `authentik/core/models.py:372`).
- Other claims always present: `iss`, `sub`, `aud` (= the client_id, a **string**), `exp`, `iat`, `auth_time`, `acr`, `amr` (when known: `pwd`, `mfa`, ...), `sid`, `jti`, plus access-token-only `azp`, `uid`, `scope` (`id_token.py:43-121, 167-175`). There is no `realm_access`.

---

## 4. Issuer and JWKS: standard discovery, Spring `issuer-uri` compatibility, quirks

**Standard OIDC discovery, per application. Spring's issuer-location decoder works unchanged if the issuer string has its trailing slash. Two quirks.**

- Mounting: the per-provider routes are under `application/o/` (`authentik/providers/oauth2/apps.py`, `mountpoints`). Routes: `<slug>/.well-known/openid-configuration` (provider-info), `<slug>/jwks/`, `<slug>/end-session/`; `authorize/`, `token/`, `userinfo/`, `introspect/`, `revoke/` are global, not per slug (`authentik/providers/oauth2/urls.py:25-68`). RFC 8414 metadata also lives at `/.well-known/oauth-authorization-server/application/o/<slug>/` (`urls_root.py:44-48`).
- `issuer_mode` defaults to **`per_provider`**: `iss` = absolute URL of `application/o/<slug>/` **with a trailing slash** (`models.py:115-122, 315-319, 365-378`). Docs: default issuer `https://authentik.company/application/o/<application_slug>/`; global mode `https://authentik.company/`, and "Global issuer mode still serves the discovery document at" the per-application path, "not at the root issuer URL" ([OAuth 2.0 provider, Issuer mode](https://docs.goauthentik.io/add-secure-apps/providers/oauth2/)).
- The discovery document contains `issuer`, `jwks_uri`, `id_token_signing_alg_values_supported: [<alg of the signing key>]`, `code_challenge_methods_supported`, and so on (`views/provider.py:47-135`).

Spring compatibility, checked against Spring Security 6.3.4:
- `NimbusJwtDecoder.withIssuerLocation(issuer)` fetches discovery, then asserts `issuer.equals(metadata["issuer"])` (`NimbusJwtDecoder.java:223-230`, `JwtDecoderProviderConfigurationUtils.java:97-100`). It builds the discovery URL as `issuerPath + "/.well-known/openid-configuration"` (`JwtDecoderProviderConfigurationUtils.java:186-192`). With the trailing slash that is `.../<slug>//.well-known/...`, but Spring Framework 6.1.14's `UriComponentsBuilder.FullPathComponentBuilder.getSanitizedPath` collapses `//` to `/` (`UriComponentsBuilder.java:921-946`, tag v6.1.14). So it resolves to authentik's real route.
  - **Quirk 1, exact string:** `app.security.issuer` (today `KEYCLOAK_ISSUER`, `application.yml:148`) must be **exactly** `https://<auth-host>/application/o/<slug>/` with the trailing slash. Without the slash, the `equals` check fails on the first token. The same applies to `ISSUER` in `frontend/src/auth/realms.ts`. oidc-client-ts appends `.well-known/openid-configuration`, adding `/` only if missing (oidc-client-ts 3.5.0 dist `oidc-client-ts.js:1031-1041`), so it is fine either way.
  - **Quirk 2, issuer per application:** the issuer, the JWKS and the end-session URL all contain the **application slug**. Renaming the slug changes `iss` and invalidates every config that holds it. The blueprint should pin the slug (e.g. `lifeplatform`). `global` mode would not help, because discovery stays per application.
- **Existing gap made more relevant:** the platform's decoder is built with `withIssuerLocation(...).build()` and no `setJwtValidator`, so it uses `JwtValidators.createDefault()`, which checks only timestamps (and x5t binding) (`NimbusJwtDecoder.java:96`, `JwtValidators.java:70-73`). **The token's `iss` and `aud` are not validated today.** Issuer is checked only on the discovery document, contrary to the comment at `SecurityConfig.java:59-60`. In authentik, any provider whose signing key equals this provider's key mints tokens this backend accepts. The stock "authentik Self-signed Certificate" is commonly reused across providers (`authentik/crypto/apps.py:51-67`). Recommendation: a **dedicated signing key** for this provider, plus `JwtValidators.createDefaultWithIssuer(issuer)` and an `aud == lifeplatform-spa` validator.

---

## 5. Signing algorithm and key rotation

- **Algorithm follows the key type** of the provider's selected `signing_key`: RSA → RS256, EC P-256/384/521 → ES256/384/512, Ed25519/Ed448 → EdDSA (`models.py:162-186, 355-363`). JWT header carries `kid` = the key pair's kid (`models.py:450-466`). Docs: "JWTs created by authentik are always signed." With a signing key, "authentik signs JWTs asymmetrically using the private key of the selected certificate-key pair" ([OAuth 2.0 provider, Signing and encryption](https://docs.goauthentik.io/add-secure-apps/providers/oauth2/)).
- **No signing key means HS256 with the client secret,** and no public key is published in JWKS (`models.py:358-360`, `views/jwks.py:150-153`; docs, same section). That breaks Spring's JWKS-based decoder, so **the blueprint must set `signing_key`.**
- Generated keys are RSA-4096 by default (`authentik/crypto/builder.py:37, 55-62`). RS256 matches Keycloak's default (`Constants.DEFAULT_SIGNATURE_ALGORITHM = RS256`, Keycloak 26.0.7 `server-spi-private/.../Constants.java:63`). Spring derives the accepted algorithms from the JWKS `alg` once, when the decoder is built (`JwtDecoderProviderConfigurationUtils.java:120-150`). Stay on RSA or EC. **Avoid EdDSA:** Spring's `SignatureAlgorithm` has no EdDSA, so the set would come back empty and building fails ("Failed to find any algorithms from the JWK set").
- **Rotation is manual, with no overlap window.** JWKS publishes **only the provider's one current signing key** (plus an encryption key if set) (`views/jwks.py:134-153`). There is no active/passive key set like Keycloak's. Rotating means generating a new key pair and repointing the provider. From then on, every outstanding access token fails signature checks. Nimbus refetches JWKS on an unknown `kid`, so new tokens verify at once. Users see one 401, which the SPA already answers with `signinSilent()` (`PlatformAuthProvider.tsx:61-67`). With a 5-minute access token the disruption is at most 5 minutes of 401s for idle tabs. Plan a rotation as a short, announced event, or keep access tokens short.
- I found no automatic rotation in the provider code. The managed "authentik Internal JWT Certificate" (360 days) is created once and not linked to providers (`authentik/crypto/apps.py:21-49`). `encode()` does not check certificate expiry (`models.py:450-466`), so an expired certificate keeps signing; Spring does not check the x5c chain either. Whether authentik warns about expiring certificates: UNCONFIRMED (a `certificate_discovery` schedule exists, `authentik/crypto/apps.py:69-80`, purpose not checked).
- Encryption (JWE) is optional and must stay **off**: with `encryption_key` set, tokens become JWEs (`models.py:329-339, 464-483`), which the SPA's `claims.ts` and Spring's decoder cannot read.

---

## 6. Lifetimes and refresh behaviour, compared with today

### Today (Keycloak 26.0.7, realm sets none of these, so import defaults apply)
| Setting | Value | Source |
|---|---|---|
| Access token | **300 s (5 min)** | `DefaultExportImportManager.java:218-219` (26.0.7) |
| SSO session idle | **1800 s (30 min)** | same file `:226-227` |
| SSO session max | **36000 s (10 h)** | same file `:228-229` |
| Auth code | 60 s | same file `:254-255` |
| Refresh token | Issued without `offline_access`; bound to the SSO session (idle/max above) | Keycloak behaviour; not re-verified in source (UNCONFIRMED) |
| Rotation | `revokeRefreshToken: true`, `refreshTokenMaxReuse: 0` in production | `deploy/keycloak/build-realms.mjs:72-75` |

The SPA renews through the refresh token: oidc-client-ts uses `refresh_token` when the user has one, else an iframe `prompt=none` (`oidc-client-ts.js:3114-3117`).

### authentik 2026.8.5 (per provider)
| Setting | Default | Source |
|---|---|---|
| `access_code_validity` | `minutes=1` | `models.py:272-279` |
| `access_token_validity` | **`hours=1`** | `models.py:280-287` |
| `refresh_token_validity` | **`days=30`** | `models.py:288-295` |
| `refresh_token_threshold` | `seconds=0` = rotate on every use | `models.py:296-305` |
| Browser session (User Login stage `session_duration`) | `seconds=0` = until the browser closes | `authentik/stages/user_login/models.py:35-43` |

Behaviour that differs from Keycloak:
1. **No refresh token unless `offline_access` is requested** (`views/token.py:151-172`), and the refresh grant itself refuses without `offline_access` in scope (`views/token.py:183-184`). Docs: "To receive a refresh token, the client must request the `offline_access` scope" ([OAuth 2.0 provider](https://docs.goauthentik.io/add-secure-apps/providers/oauth2/)). The `offline_access` scope mapping must also be attached to the provider (shipped as `goauthentik.io/providers/oauth2/scope-offline_access`). With today's `scope: 'openid profile email'`, the SPA would get **no refresh token** and fall back to iframe renewal against `redirect_uri` `/`, loading the whole SPA in a hidden iframe every few minutes. **Add `offline_access` to the SPA's scope.**
2. **Rotation matches today's.** With threshold 0, every refresh issues a new refresh token and marks the old one revoked (`views/token.py:216-245`). Reusing a revoked one is refused with `invalid_grant` and logs a `SUSPICIOUS_REQUEST` event (`token/refresh_token.py:39-47`). Unlike the claim in `build-realms.mjs:72-73` about Keycloak ("the thief's use ends the real session"), **authentik does not revoke the newer token or the session** on reuse. The event can drive a notification rule. (Keycloak's reuse behaviour is UNCONFIRMED here.)
3. **Sliding idle window, no absolute cap.** Each refresh gives a fresh `now + refresh_token_validity` (`views/token.py:221`). So `refresh_token_validity` acts as an **idle** timeout. Nothing in the refresh path checks `auth_time` or the browser session (`token/refresh_token.py`, `views/token.py:178-245`), so there is **no equivalent of Keycloak's 10-hour SSO max** for an open tab. Closest match to today: `access_token_validity: minutes=5`, `refresh_token_validity: minutes=30`, User Login stage `session_duration: hours=10`. A hard 10-hour cap on refresh chains would be a ticket (e.g. a backend check on `auth_time`, which every token carries, `id_token.py:101`).
4. **Logout does not kill refresh tokens.** Ending the authentik session deletes the session's access-token rows and sends back-channel logout (`signals.py:89-118`). Refresh tokens deliberately survive the session (`models.py:687-691`, `on_delete=SET_DEFAULT`). Access-token JWTs stay valid to Spring until `exp` in either IdP, because there is no introspection. To end refresh on sign-out, set oidc-client-ts `revokeTokensOnSignout: true` (default false, `oidc-client-ts.js:2483-2484`). It posts to authentik's `revoke/`, which deletes the token (`views/token_revoke.py:63-85`). User deactivation deletes all of the user's tokens (`signals.py:121-130`).

---

## 7. Adjacent findings (outside the five bullets, but they bind the spec)

- **E2E password grant:** `frontend/e2e/creditLife.ts:43-62` gets tokens with `grant_type=password` on the confidential `lifeplatform-app`. authentik accepts `password` as a grant type, but treats it as client-credentials: the `password` must be an **app password token** (`intent=app_password`) of that user, not the user's real password (`views/token.py:91-93`, `token/client_credentials.py:38-61`). Tokens from it go through the same `IDToken.new` path, so they carry the same mapped claims. The e2e helper must create app passwords (or drive the browser flow). This belongs to the e2e sign-in work in the map's "Not yet specified".
- `amr` contains `mfa` when the login used MFA devices (`id_token.py:105-121`). It could let the backend check that staff signed in with the texted code; whether the staff SMS stage sets `mfa_devices` is ticket 01's question (UNCONFIRMED).

---

## Verdict

**Feasible: yes.** One authentik OAuth2 provider + application can issue the tokens universal login needs. No authentik code is needed, only configuration in the blueprint plus a handful of platform edits:

1. **Provider** (blueprint): `client_type: public`; `redirect_uris`: strict `authorization` `https://<APP_DOMAIN>/` (dev `http://localhost:5173/`) + strict `logout` same; a **dedicated RSA `signing_key`**; `include_claims_in_id_token: true` (load-bearing); `sub_mode: user_uuid`; `issuer_mode: per_provider`, application slug pinned; `access_token_validity: minutes=5`, `refresh_token_validity: minutes=30`, `refresh_token_threshold: seconds=0`; no `encryption_key`; property mappings: `openid`, a custom `profile` (no `groups`), `offline_access`, and a custom `lifeplatform` scope; **not** `entitlements`. Plus an expression policy requiring S256 PKCE, and a User Login stage `session_duration: hours=10`.
2. **SPA**: `ISSUER = <auth>/application/o/<slug>/` (trailing slash), `scope: 'openid profile lifeplatform offline_access'` (drop `email`), `revokeTokensOnSignout: true`; `claims.ts` reads `roles` instead of `realm_access.roles`.
3. **Backend**: `app.security.issuer` = the same exact string; `SecurityConfig.realmRoles` reads `roles`; **add issuer and audience validators** (a gap today, riskier with authentik's shared keys); the fake-token test helpers build `roles`.
4. **Gaps to ticket (not silent drops):** no absolute 10-hour cap on refresh chains; refresh-token reuse does not end the session; key rotation has no overlap window; PKCE S256 enforcement needs a policy; logout leaves refresh tokens unless the SPA revokes them.

### Claims today → authentik mapping

| Claim today (access token) | Today's source (Keycloak) | authentik source | Mapping (scope `lifeplatform` unless stated) | Consumer change |
|---|---|---|---|---|
| `realm_access.roles` | realm roles, built-in mapper | group membership (`user.all_groups()`), groups named `staff`/`agent`/`customer`/`regulator` + job roles (`ADMIN`, `UNDERWRITER`, ...) | `"roles": [g.name for g in request.user.all_groups()]` | `SecurityConfig.realmRoles`, `claims.ts`, test token builders: read top-level `roles` |
| `tenant_id` | user attribute mapper | user attribute `tenant_id` (string) | `"tenant_id": request.user.attributes.get("tenant_id")` | none |
| `party_id` | user attribute mapper | user attribute `party_id` | `"party_id": request.user.attributes.get("party_id")` | none |
| `home_branch` | user attribute mapper | user attribute `home_branch` | `"home_branch": request.user.attributes.get("home_branch")` | none (claim not in the ticket list, but consumed by `UnderwritingController:198`) |
| `sub` (Keycloak user UUID) | built in | built in, `sub_mode: user_uuid` → `user.uuid` | provider setting, not a mapping | admin-API adapters store the authentik user UUID |
| `preferred_username` (= phone) | built in (`profile`) | `user.username` | custom `profile` mapping: `"preferred_username": request.user.username` | none |
| `name` | built in (`profile`) | `user.name` | custom `profile` mapping: `"name": request.user.name` | none |
| `email` | built in (`email` scope) | `user.email` | drop the `email` scope (email is never a channel); claim absent, already tolerated | SPA scope string |
| `iss` | `.../realms/lifeplatform` | `https://<auth>/application/o/<slug>/` | provider/app setting | `app.security.issuer`, `realms.ts ISSUER` (exact, trailing slash) |
| `aud` | (not checked) | `lifeplatform-spa` (string) | built in | add an audience validator |
| signature | RS256, realm key set | RS256 from the dedicated RSA key; JWKS `application/o/<slug>/jwks/` | provider `signing_key` | none (discovered) |
