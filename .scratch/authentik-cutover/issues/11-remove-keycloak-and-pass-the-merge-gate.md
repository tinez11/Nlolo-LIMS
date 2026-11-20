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

- [ ] No Keycloak code, configuration or image remains, and the grep finds only history and docs
- [ ] The full backend suite passes, and the result is reported with counts
- [ ] The full vitest suite passes
- [ ] The full e2e suite passes
- [ ] The fresh-bootstrap rehearsal signs in to every workspace
- [ ] The whole-branch review's findings are fixed or explicitly accepted by the user

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
