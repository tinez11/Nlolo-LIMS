# Can Authentik issue the tokens universal login needs?

Type: research
Status: resolved
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

Universal login means one realm, one SPA client (`lifeplatform-spa`, used through `react-oidc-context` with PKCE) and one sign-in for every console: staff, agents, customers and the regulator. The workspace is chosen per request by the `X-Workspace` header, and `SecurityConfig.authoritiesFor` turns the token into authorities. Read `iam/infrastructure/SecurityConfig.java`, `Workspace.java`, `TenantContextFilter.java`, `frontend/src/auth/claims.ts` and `realms.ts` for what the token must carry.

In Authentik:

- Can one OAuth2/OIDC provider and application serve all four consoles' redirect URIs, as a public client with PKCE?
- Can scope or property mappings put a plain `roles` claim (from group membership), a `tenant_id` and a `party_id` (from user attributes) into the **access token**, not just the ID token?
- Are the issuer and JWKS URLs standard OIDC discovery, so Spring's `issuer-uri` resource-server configuration works unchanged? Are there any issuer-per-application quirks?
- What signing algorithm is used, and how are keys rotated?
- What are the token lifetimes and refresh-token behaviour, compared with today's?

Answer from primary sources for the current stable release, citing each claim.

## Findings

[research/05-tokens-and-universal-login.md](../research/05-tokens-and-universal-login.md)

## Answer

Feasible (authentik 2026.8.5).

- **One provider and application:** one public OAuth2 provider and one application serve all four consoles. Each origin needs one exact redirect address (the SPA already sends `${origin}/`) plus one logout address.
- **One custom scope mapping** puts these into the access token:
  - `roles`, from `user.all_groups()`
  - `tenant_id`
  - `party_id`
  - `home_branch`, which the backend reads (`UnderwritingController:198`) though the question didn't list it
- **The blueprint must get these right:**
  - **"Include claims in id_token" stays ON.** The access token is built from the ID token, so turning it off drops every mapped claim and every request gets a 403.
  - **`sub_mode: user_uuid`.** The default is a SHA-256 hex string, but `AccessExpressions.userIdOf` expects a UUID.
  - **Scopes:** don't attach the stock `entitlements` mapping, which also emits `roles`. Add `offline_access`, or no refresh token is issued. Drop `email`.
  - **Issuer** is per application: `https://<auth>/application/o/<slug>/`. Spring's `withIssuerLocation` discovery works, but the configured issuer must match exactly, including the trailing slash.
  - **Signing:** a dedicated RSA key. With no key, authentik signs HS256 with no public key. Not EdDSA, which Spring 6.3 can't use.
- **Gaps to carry into the spec:**
  - PKCE S256 isn't enforced by default; a policy on `oauth_code_challenge_method` can enforce it.
  - Key rotation has no overlap, because the JWKS lists one key.
  - There is no 10-hour maximum session: each refresh resets the refresh validity, so it acts as an idle timeout only.
  - Reusing an old refresh token is refused but doesn't end the session.
  - Logout leaves refresh tokens alive unless the SPA sets `revokeTokensOnSignout: true`.
- **Lifetimes:** today Keycloak gives a 5-minute access token, a 30-minute idle session and a 10-hour maximum. authentik's defaults are 1 hour and 30 days. The proposal is 5 minutes, 30 minutes, and a 10-hour login session.
- **E2E:** authentik's `password` grant expects an app-password token, not the user's password. This affects `frontend/e2e/creditLife.ts`.
- **A security gap that exists today, independent of the migration** (reported by the research, not yet proven): `SecurityConfig.lazyDecoder` builds `NimbusJwtDecoder.withIssuerLocation(issuer).build()` with no explicit `JwtValidators.createDefaultWithIssuer` and no audience validator. If the builder's default validator checks only the token's timestamps, a token's `iss` and `aud` are never checked, even though the comment at `SecurityConfig.java:59` assumes an issuer check. Today the risk is small, because one realm signs every token. Under authentik, any provider sharing the signing key could mint accepted tokens. The spec adds issuer and audience validators either way; a test should confirm the current behaviour.
