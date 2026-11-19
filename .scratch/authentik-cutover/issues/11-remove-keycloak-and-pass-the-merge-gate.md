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
