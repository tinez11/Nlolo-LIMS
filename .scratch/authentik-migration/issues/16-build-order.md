# Build order inside the cut-over branch

Type: grilling
Status: resolved
Blocked by: 14, 15
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

The cut-over is one branch that removes Keycloak entirely, merged when backend, frontend and e2e all sign in through Authentik. In what order does the work land, so each step can be checked on its own and the dev stack is never left with no way to sign in for long?

The pieces to order:

- **Dev compose:** Authentik (server and worker) plus blueprints, replacing Keycloak.
- **Blueprints:**
  - sign-in flow (variant A), staff code stage, recovery redirect flow
  - provider and mappings (UUID `sub`, filtered `roles`, `tenant_id`, `party_id`, `home_branch`, RSA key, PKCE policy)
  - the four groups
  - notification rule and webhook
  - branding
- **Backend:**
  - `SecurityConfig`: the `roles` claim, plus new issuer and audience validators
  - the three adapters rewritten for Authentik's API
  - the two webhook endpoints with bearer secrets
  - `StaffSignInCodes` taking over the caps
  - the `ResetCodes` logic moved in
  - the `/reset` endpoints
  - `ProductionSettingsGuard`
- **Frontend:**
  - `claims.ts` and `realms.ts`
  - `revokeTokensOnSignout`
  - the 10-hour `auth_time` sign-out
  - the `/reset` page
- **Backend tests:** the contract and integration tests that name Keycloak, the fake-token helpers that build `realm_access`, and the test `application.yml`. This is mechanical work, folded in here from the map's fog.
- **E2E:** per [How e2e tests sign in under Authentik](14-e2e-sign-in.md).
- **Deployment:** per [Deploying and operating Authentik](15-deployment-and-operations.md).
- **Deletions:** `backend/keycloak/{extensions,themes}`, `deploy/keycloak`, the realm JSON, `KeycloakSignature`.

Also decide which verification runs at which step, following the repo's cadence: affected tests within steps, and full backend, vitest and e2e at the end. Only one heavy run at a time.

## Answer

The user's decisions, 2026-11-19.

**Step 0, on `main` before the branch:** fix token validation. First a test proving today's behaviour, then explicit issuer and audience validators in `SecurityConfig.lazyDecoder` against Keycloak. The gap was found by [Can Authentik issue the tokens universal login needs?](05-tokens-and-universal-login.md). The cut-over then changes only the issuer value.

**Amended 2026-11-19, while doing step 0 (the user chose (a)):** step 0 adds the **issuer** check only. Keycloak's
access tokens carry no `aud` claim (no audience mapper in the realm), so the **audience** validator moves into step 2,
where Authentik's tokens carry `aud` natively. The gap is confirmed in Spring Security 6.3.4: `NimbusJwtDecoder`
defaults to `JwtValidators.createDefault()`, and only `JwtDecoders.fromIssuerLocation` adds the issuer check. It was
proven live against dev Keycloak: a token minted via `127.0.0.1:8081`, with the same key but a different `iss`, now
gets 401, while `localhost:8081` gets 200.

**Inside the cut-over branch.** Each step ends with its affected tests and a manual check in the dev stack. Keycloak stays in the **dev** compose until step 3, so the dev stack can always sign someone in; production never runs both.

1. **Authentik in dev:**
   - the dev compose service with memory limits (server 1 GiB, worker 512 MiB)
   - production blueprints: sign-in flow variant A, the code stage bound by `STAFF_SIGN_IN_CODE`, provider and mappings, RSA key, PKCE policy, superuser refusal, four groups, branding
   - dev blueprints: 14 users, app passwords, the `e2e-sign-in-code` group

   *Check:* sign in through the browser, then decode a token and confirm the `roles`, `tenant_id`, `party_id`, `home_branch` and UUID `sub` claims.
2. **Backend reads Authentik tokens:**
   - the `roles` claim in `SecurityConfig`
   - the issuer value
   - renamed settings
   - fake-token test helpers and the test `application.yml`

   *Check:* affected backend tests, plus API calls with a real Authentik token.
3. **Frontend signs in with Authentik:**
   - `claims.ts` and `realms.ts`
   - the OIDC config
   - `revokeTokensOnSignout`
   - the 10-hour `auth_time` sign-out

   *Check:* affected vitest, plus signing in to every workspace by hand. **Keycloak leaves the dev compose.**
4. **Login management:** the three adapters on Authentik's API (400-as-duplicate, temporary password, refresh-token sign-out, per-object rights). *Check:* contract tests, plus the Users screen, agent onboarding and portal invite by hand.
5. **The two webhooks:**
   - **Sign-in code:** bearer secret, status-to-message mapping, caps in `StaffSignInCodes`.
   - **Password changed:** bearer secret, dedupe, notification rule and policy.

   *Check:* affected tests, plus a live code through the mock SMS gateway.
6. **Password reset:**
   - the `/reset` page
   - backend endpoints (`ResetCodes` logic moved in, recovery link)
   - the recovery redirect flow

   *Check:* affected tests, plus a reset by hand in English and Swahili.
7. **E2E:** the shared sign-in helper, the `creditLife.ts` app password, and the sign-in code spec. *Check:* those specs alone.
8. **Deployment:**
   - the `-auth` image
   - the server compose
   - backup and restore
   - Caddy
   - `add-user.sh`
   - `remote-release.sh`
   - `smoke.sh`
   - `ProductionSettingsGuard`
   - `DEPLOYMENT-GUIDE.md`
9. **Deletions:** `backend/keycloak/`, `deploy/keycloak/`, the realm JSON, `KeycloakSignature` and the old auth Dockerfile. A final grep confirms no `keycloak` is left outside history and docs.

**Merge gate,** one heavy run at a time, in this order:
1. **Full backend suite** (about 80 minutes; stop the dev backend first).
2. **Full vitest.**
3. **Full e2e** at `--workers=2`.
4. **Fresh-bootstrap rehearsal:** `down -v` and up of the dev stack from nothing, plus a local dry run of the server compose.

Then a solo whole-branch review, and merge `--no-ff`.
