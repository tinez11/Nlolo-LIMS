# Keycloak realm imports

Minimal, importable stub realms for local development (see
`docs/superpowers/plans/2026-08-05-m0-platform-bootstrap.md`, Task 7).

- Client secrets (`dev-secret-*`) are dev-only placeholders, never used outside
  local `docker compose up`.
- The `staff` realm's six roles (UNDERWRITER, CLAIMS_ASSESSOR, CLAIMS_MANAGER,
  FINANCE_OFFICER, CUSTOMER_SERVICE_REP, ADMIN) are quoted verbatim from
  `docs/04-api-contracts.md`.
- `TIRA_READ_ONLY` (regulators realm) and the `lifeplatform-app` client ID are
  this project's own naming for the read-only TIRA portal concept described
  in `docs/04-api-contracts.md` §3 -- not sourced from an external spec.
- No user federation, custom claim mappers (e.g. `party_id`, `agentOfRecord`),
  or production secrets are configured here -- that lands with the modules
  that need them, starting M1.
