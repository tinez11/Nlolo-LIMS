# What does it take to run Authentik here?

Type: research
Status: resolved
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

What does it take to run Authentik in this repo's dev compose (`backend/infra/docker-compose.yml`) and the server compose (`deploy/server/compose.yml`)? The dev box has 7.8 GB of RAM and already runs Postgres, the backend, SeaweedFS and others.

- What is the current stable version, and which containers does it need: server, worker, Redis? Is Redis still required in the latest release?
- What are its memory and CPU needs at idle and under light load, compared with Keycloak 26?
- Can it use a separate database on the existing Postgres?
- **Blueprints:** how are they mounted and applied at startup, and are they idempotent? Can they declare flows, stages, groups, providers, applications, property mappings and branding? How do secrets get in (environment variables)?
- **Languages:** does the login and recovery UI support **Swahili (`sw`)**? If not, can translations be added?
- **Theming limits:** what can branding and custom CSS change on the flow pages (logo, background image, colours, layout), and what can't they? See `backend/keycloak/themes/lifeplatform/login` for today's theme.
- How is the first admin bootstrapped non-interactively?

Answer from primary sources for the current stable release, citing each claim.

## Findings

[research/06-running-authentik.md](../research/06-running-authentik.md)

## Answer

It runs fine in both compose files. Swahili is missing (authentik 2026.8.5).

- **Containers:** two, a server and a worker, both from `ghcr.io/goauthentik/server`. Redis has not been needed since 2025.10.
  - It uses its own `authentik` database on the existing Postgres 16, which needs connection headroom: about 50% more connections than older releases.
  - No Docker socket or `user: root`, because there are no outposts.
  - Set the Postgres SSL mode explicitly: the docs and the source code disagree on its default.
- **RAM:** roughly neutral against Keycloak (about 1 GB for both containers versus about 1.25 GB, and `keycloak-config` goes away). This is not measured. Set a `mem_limit` and check `docker stats` early in the cut-over branch.
- **Blueprints:** they cover everything needed.
  - Mount them on the worker in a subfolder such as `/blueprints/lifeplatform`.
  - Applied at startup and whenever a file changes. Re-applying is safe, because entries are matched on `identifiers`, and each file applies in one transaction.
  - They declare flows, stages, groups, providers, applications, mappings and branding with its CSS.
  - Secrets come in through `!Env` or `!File`.
- **Swahili: missing.** The stock image ships no `sw` web or backend translations, so pages silently fall back to English. Options:
  - a custom-built image, patched and rebuilt on every upgrade
  - contributing upstream through Transifex

  Text entered in the admin UI (flow titles, labels) is single-language either way. This is now its own ticket: [Swahili on the Authentik login pages](11-swahili-login-pages.md). The SMS wording is unaffected, because it stays in the backend.
- **Theming:** the same feel is achievable, not the same page. Logo, favicon, title, background photo, a sidebar layout, and colours and fonts through CSS variables and `::part` all work. Our own markup, the brand-panel display text and our custom JavaScript cannot be carried over.
- **First admin:** `AUTHENTIK_BOOTSTRAP_PASSWORD_HASH` (or `_PASSWORD`) and `_TOKEN` on the worker. They are read on first start only and must come from `.env`.
- **Unconfirmed:** idle memory, a healthcheck command, and an iframe background layout found only in the source.
