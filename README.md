# Nlolo Life Platform

A life insurance core platform for Tanzania: products, underwriting, policies, billing and payments, claims and
payouts, savings and investment accounts, agents and commission, reinsurance, IFRS 17 accounting and regulatory
reporting -- with consoles for staff, agents and customers.

## The products it runs

Term life, endowment, whole life and education savings (premium-rated, with cash values and paid-up); with-profits;
money-back payout schedules; savings accounts; fixed-term deposits; **term accounts** (regular contributions at a rate
locked per policy, nothing out until maturity); immediate and deferred annuities and pensions; unit-linked; family
and group funeral; group life and credit life schemes. Every product charge is optional.

## Repository layout

| Path | What is there |
|---|---|
| `backend/` | Spring Boot 3.3 / Java 21 modular monolith (Spring Modulith), one module per package under `src/main/java/tz/co/nlolo/lifeplatform/` (party, product, underwriting, policy, billing, payment, claims, benefitpayout, accumulation, annuity, unitlinked, bonus, policyloan, distribution, reinsurance, finaccounting, regreporting, reporting, communication, document, omnichannel, audit, refdata, dashboard, iam). |
| `backend/db-migrations/<module>/` | Flyway migrations, one history per module (`backend/scripts/migrate.sh`). Postgres with row-level security per tenant. |
| `backend/api/openapi/` | One OpenAPI spec per module -- the contract the console's types are generated from. |
| `backend/infra/` | Docker Compose for the local stack: Postgres, Keycloak 26, SeaweedFS, Redis, Mailpit, mobile-money and SMS stubs. |
| `backend/keycloak/` | The four realms (staff, agents, customers, regulators) and the login theme. |
| `backend/docs/` | Architecture (01-08, design-era -- see the notes at the top of each), runbooks (09, 10), and `superpowers/specs` + `plans` for every feature since. |
| `frontend/` | The console: Vite + React + TypeScript, one SPA serving the staff, agents and customers consoles. |
| `deploy/` | The single-server deployment (Caddy, Compose, release and bootstrap scripts). |
| `PRODUCT.md`, `DESIGN.md` | Who the consoles are for, and the design system. |

## Running it locally

1. **`backend/docs/09-local-development.md`** -- the containers, migrations, scheduled jobs, the backend
   (`./mvnw -B -o spring-boot:run -Dspring-boot.run.profiles=local`, port 8080), the seeder, and the console
   (`npm run dev` in `frontend/`, port 5173). Read its "four things" section before your first run.
2. **`backend/docs/10-frontend-development.md`** -- the console in more depth: Keycloak's console client, generated
   API types, Vitest and Playwright.
3. **`backend/keycloak/README.md`** -- the dev users and what each role may do.

Deploying: **`deploy/README.md`**.

## Working rules worth knowing

- Migrations never run on startup: run `backend/scripts/migrate.sh local` (and `configure-db.sh local`) after pulling
  one, then restart the backend.
- Run Maven on the host with a JDK 21, never inside a container (Testcontainers cannot reach its databases from
  there).
- The full backend suite takes over an hour; run the test classes a change touches, and the whole suite before a
  merge.
- Money travels as a decimal string with its currency, never as a JSON number (`PRODUCT.md`).
