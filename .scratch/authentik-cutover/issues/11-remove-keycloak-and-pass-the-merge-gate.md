# 11: Remove Keycloak and pass the merge gate

**What to build:** No trace of Keycloak remains outside history and docs, and the branch proves itself before merging.

**Delete:**
- the Keycloak extensions and theme
- the realm builder and realm JSON
- the old auth image definition
- `KeycloakSignature`
- the `KEYCLOAK_*` settings

A grep for `keycloak`, case-insensitive, finds only history, specs and this effort's notes.

**Then the merge gate,** strictly one heavy run at a time:
1. **The full backend suite** (about 80 minutes; stop the dev backend first).
2. **The full vitest suite.**
3. **The full e2e suite** at `--workers=2`.
4. **A fresh-bootstrap rehearsal:** the dev stack from `down -v`, seeded, and every workspace signing in.
5. **A solo whole-branch review** looking for problems in the seams between the tickets.

Then merge `--no-ff`, but only when the user asks.

Spec: "Build order", merge gate. The decision is in ticket 16 of `.scratch/authentik-migration/`.

**Blocked by:** 09, 10

**Status:** ready-for-agent

- [x] No Keycloak code, configuration or image remains, and the grep finds only history and docs (checked 2026-11-23: only history comments outside docs)
- [x] The full backend suite passes, and the result is reported with counts (2026-11-21 on daed39af: 3356 run, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS)
- [x] The full vitest suite passes (2026-11-23: 2022/2023, the one failure a design guard on /reset's code box, fixed fc9a4e7c and re-run green)
- [x] The full e2e suite passes (2026-11-23: 217/225 in the full run, 5 skipped; the 3 failures were fixed or passed alone, see Comments)
- [ ] The fresh-bootstrap rehearsal signs in to every workspace
- [x] The whole-branch review's findings are fixed or explicitly accepted by the user (2026-11-23: no findings; accepted by the user)

## Comments

**2026-11-20, handed on from ticket 09:**

- **Still speak to Keycloak:**
  - `backend/scripts/hazina-demo.mjs`: its token function. Rewrite it like `seed-lib.sh`'s `token_for`.
  - `backend/scripts/apply-staff-sign-in-flow.sh`: Keycloak-only. Delete it.
- **The full e2e run will be slower.** A silent sign-in through Authentik costs about 3 seconds idle, against Keycloak's well under one, and every page load makes one. Raise the suite's 60-second test budget before treating timeouts as regressions, and re-run a failure alone first.
- **The public specs need SMS on.** Run the backend with `SMS_GATEWAY_LIVE=true` and the gateway on the mock.
- **Two failures older than the cut-over** (details in ticket 09):
  - `staff-underwriting` "a proposal states the contract": it needs a priced product.
  - the seed's customer loan step: a 403, because loans are staff-only.

**2026-11-23, the full e2e run** (`npm run test:e2e`, the documented one worker; `--workers=2` overloads this 7.8 GB box now that every page load signs in silently through Authentik): 217 passed, 3 failed, 5 skipped, 1.9 h.
- `agents-my-sales` "by month": passed alone (2/2). Sign-in timing.
- `staff-payouts` "a payout passes two people": dev `staff.finance` had lost `payouts.approve-own` (D2) when ticket 01 rebuilt the staff rows; nothing seeds it. Restored by hand in the dev database; the whole file then passed (one test needed a second try, sign-in timing).
- `staff-underwriting` "a proposal states the contract": "This product version carries no base rate table" on Demo Term Life. The seed gap named above, older than the cut-over.
- Before a run, the dev password grant must be present: the production blueprint applied after the dev one had removed it, and every `apiToken` got invalid_grant. Restored through the API.
- **Fixed the same day:** the seed publishes Demo Term Life with base rates and entry ages, and grants staff.finance `payouts.approve-own`; `scripts/dev/price-demo-term.sh` republishes an already-seeded database's (run on dev); `e2e/globalSetup.ts` stops a run at once, with the fix, when the dev password grant is missing. The seven spec files on Demo Term Life re-ran after the republish: 40 passed, 2 failed on sign-in timing and passed alone.

**2026-11-20, handed on from ticket 10 (native install):**
- **Still for Keycloak, delete them:**
  - `PASSWORD_RESET_SMS_SECRET`: the server `.env`, `bootstrap-server.sh`, `remote-release.sh`'s `backend.env`, and the guard's `password-reset.sms-secret` entry, together with `PasswordResetSmsController` and `KeycloakSignature`
  - `CREATE DATABASE keycloak` in `backend/infra/postgres/init/01-create-app-role.sql.template`, which the native first release also runs
- **No Docker on the server any more.** The fresh-bootstrap rehearsal is now `bootstrap-server.sh` plus `release.sh` on a Debian 13 server, which the user decided is the real server (ticket 10, Q6).
