# What does it take to run Authentik here? (research, ticket 06)

Researched 2026-11-19 against **authentik 2026.8.5**. Sources are the docs at docs.goauthentik.io and the `goauthentik/authentik` source at tag `version/2026.8.5`. Source links point at that tag. Anything I could not confirm from a primary source is marked **UNCONFIRMED**.

## 0. Version

- **Current stable: 2026.8.5.** GitHub marks `version/2026.8.5` as the latest release (https://github.com/goauthentik/authentik/releases/latest). The compose file the docs ship pins `ghcr.io/goauthentik/server:${AUTHENTIK_TAG:-2026.8.5}` (https://docs.goauthentik.io/compose.yml, lines 34 and 56).
- The 2026.8 release notes list patches 2026.8.1 through 2026.8.5 (https://docs.goauthentik.io/releases/2026.8/). The docs site's version menu calls "2026.8" a pre-release. That label points at the next development line, not at 2026.8. The tagged release and the shipped compose file both settle the stable version as 2026.8.5.
- **Breaking change to note for our proxy setup** (Caddy on the server): from 2026.8, `X-Forwarded-*` headers are honoured only from `AUTHENTIK_LISTEN__TRUSTED_PROXY_CIDRS` (https://docs.goauthentik.io/releases/2026.8/). The default list already includes `10.0.0.0/8`, `172.16.0.0/12` and `192.168.0.0/16`, so Docker bridge networks are covered (https://docs.goauthentik.io/install-config/configuration/, "Listen Settings").

## 1. Containers, and whether Redis is needed

- **Two authentik containers, one image.** The shipped compose runs `server` (`command: server`) and `worker` (`command: worker`), both from `ghcr.io/goauthentik/server`, plus `postgresql` (`postgres:16-alpine`). There is no Redis service (https://docs.goauthentik.io/compose.yml, lines 1-67).
- The architecture page names only Server (core plus embedded outpost), Worker and PostgreSQL (https://docs.goauthentik.io/core/architecture/).
  - The worker "executes background tasks, such as sending emails, the event notification system, and everything you can see on the System Tasks page".
- **Redis is no longer required.** It was dropped in **2025.10**: "we've also migrated caching, the embedded outpost, and WebSocket to Postgres, fully removing the need for Redis … Redis-related settings have also been removed" (https://docs.goauthentik.io/releases/2025.10/). Tasks had already moved to Postgres in 2025.8 (same page).
  - Our dev compose keeps its `redis` service, but only because the backend uses it (`backend/infra/docker-compose.yml:36`).
- **Trade-off of dropping Redis:** "it is expected that authentik will use roughly 50% more database connections to Postgres" (https://docs.goauthentik.io/releases/2025.10/). `conn_max_age` defaults to `0`, which closes connections after each request (https://docs.goauthentik.io/install-config/configuration/, "Connection management"; `authentik/lib/default.yml` in the 2026.8.5 source).
- **Docker socket:** the shipped worker mounts `/var/run/docker.sock` and runs as `user: root`. Both exist only to deploy outposts automatically (https://docs.goauthentik.io/install-config/install/docker-compose/, "Docker socket"). We use OIDC only and need no outposts. The docs allow removing the mount and deploying outposts by hand. **Recommendation:** drop the socket and `user: root` in both of our compose files.
- **Volumes:**
  - `/data` holds uploaded files: logos, flow backgrounds, icons.
  - `/certs` is optional.
  - `/templates` is "used for custom email templates", so it is not used for login pages (https://docs.goauthentik.io/core/architecture/).
  - Without `/data` mounted, media fields take external URLs (same page).
- The shipped compose sets `shm_size: 512mb` on both containers (compose.yml lines 39 and 58). **UNCONFIRMED:** whether this counts against RAM at idle. Shared memory is only charged when it is used.
- **Ports:** HTTP on 9000 and HTTPS on 9443 inside the container (compose.yml lines 35-37). Our dev `8081` mapping becomes `8081:9000`.

## 2. Memory and CPU compared with Keycloak 26

| | Primary-source figure |
|---|---|
| authentik minimum host | "at least 2 CPU cores and 2 GB of RAM". This is for the whole compose stack, Postgres included (https://docs.goauthentik.io/install-config/install/docker-compose/, "Requirements") |
| authentik process model | server: gunicorn `web.workers: 2` × `web.threads: 4`; worker: `worker.processes: 1` × `worker.threads: 2` (`authentik/lib/default.yml` lines 162-174; https://docs.goauthentik.io/install-config/configuration/) |
| authentik Helm example | server and worker each show `requests/limits memory: 512Mi`, `cpu: 100m`, **commented out** (the default is `resources: {}`) (https://github.com/goauthentik/helm/blob/main/charts/authentik/values.yaml, server and worker `resources`) |
| Keycloak (26.x HA guide) | "The base memory usage for a Pod including caches of Realm data and 10,000 cached sessions is 1250 MB of RAM". Heap is 70% of the container limit, plus about 300 MB non-heap. 1 vCPU per 15 password logins/s (https://www.keycloak.org/high-availability/multi-cluster/concepts-memory-and-cpu-sizing) |

- **Idle and light-load RSS for authentik: UNCONFIRMED.** Neither the docs nor the release notes publish a measured idle figure. The Helm 512 MiB-per-container example is a commented suggestion, not a measurement. Third-party pages quote 300-500 MB per container, but they are not primary sources, so I did not rely on them.
- **Inference (not measured):** server plus worker sized at the Helm example comes to about 1 GiB, against Keycloak's documented 1250 MB base. Swapping one for the other should be roughly **RAM-neutral to slightly favourable** on the dev box, for three reasons:
  - Authentik needs no Redis.
  - Authentik needs no second Postgres; it reuses ours (see §3).
  - `keycloak-config` (the one-shot `adorsys/keycloak-config-cli`, `deploy/server/compose.yml:115`) disappears because blueprints replace it.
- **Recommendation:** give `server` and `worker` an explicit `mem_limit`. Start at 768m each, then measure with `docker stats` in the first build step before tightening. The server compose already uses this pattern (`mem_limit: 2500m` on the backend, `deploy/server/compose.yml:147`).
- To trim the dev footprint further, the documented knobs are `AUTHENTIK_WEB__WORKERS` and `AUTHENTIK_WORKER__PROCESSES` / `__THREADS` (https://docs.goauthentik.io/install-config/configuration/). **UNCONFIRMED:** how much each step saves.

## 3. A separate database on the existing Postgres

- **Yes.** Connection settings are plain environment variables: `AUTHENTIK_POSTGRESQL__HOST`, `__PORT` (default 5432), `__NAME`, `__USER` and `__PASSWORD` (https://docs.goauthentik.io/install-config/configuration/, "Connection settings").
  - The shipped compose wires exactly these to its own `postgresql` service (compose.yml lines 29-32 and 51-54). Pointing them at our `postgres:5432` instead is a value change, not a structural one.
  - `AUTHENTIK_POSTGRESQL__DEFAULT_SCHEMA` (default `public`) "can only be set before first start" (same page, "Advanced Settings").
- **Version:** authentik has required PostgreSQL ≥ 14 since 2024.6 (https://docs.goauthentik.io/releases/2024.6/). Our image is `postgres:16` (`backend/infra/postgres/Dockerfile`), and the shipped compose also uses 16.
- **TLS:** the 2026.8.5 source defaults `sslmode: disable` (`authentik/lib/default.yml` line 24), which suits our internal network. The configuration page lists `verify-ca` as the default for `AUTHENTIK_POSTGRESQL__SSLMODE`, so the docs and the source disagree. **Set `AUTHENTIK_POSTGRESQL__SSLMODE` explicitly** so the docs-vs-source discrepancy cannot bite. If TLS is ever enabled, 2025.10 requires TLS 1.3 or Extended Master Secret (https://docs.goauthentik.io/releases/2025.10/).
- **Repo work this implies:**
  - Replace `backend/infra/postgres/init/02-keycloak-owner.sh` with an `authentik` database and role, using the same own-login pattern.
  - Init scripts run only on an empty volume, so existing dev volumes need a one-off manual `psql`.
  - The `max_connections` headroom must cover the backend's Hikari pool (max 24, `backend/src/main/resources/application.yml:67`) plus authentik's larger connection use. **UNCONFIRMED:** I found no `max_connections` override in the repo, so Postgres's stock 100 applies.

## 4. Blueprints

- **Where they're mounted:** "The authentik container by default looks for blueprints in `/blueprints`". Its subdirectories `default`, `example` and `system` ship inside the image, and "Any additional `.yaml` file in `/blueprints` will be discovered and automatically instantiated, depending on their labels" (website/docs/customize/blueprints/index.mdx lines 87-93, https://docs.goauthentik.io/customize/blueprints/).
  - The setting is `blueprints_dir: /blueprints` (`authentik/lib/default.yml` line 160).
  - Instances refer to a file "mounted into the authentik (worker) container" (index.mdx line 23).
  - **So:** mount the repo's blueprints into the **worker** at a subdirectory such as `/blueprints/lifeplatform`, not over `/blueprints`. Mounting over `/blueprints` would hide the built-in `system` and `default` blueprints. This is the same leaf-mount lesson as today's Keycloak theme mount (`backend/infra/docker-compose.yml:118-128`).
  - "To disable existing blueprints, an empty file can be mounted over the existing blueprint" (index.mdx line 95).
- **When they're applied:**
  - `blueprints_discovery` is scheduled with `send_on_startup=True` and then hourly (`authentik/blueprints/apps.py` lines 164-177).
  - A file-watcher middleware applies a blueprint when its file is created or modified (apps.py lines 159-161; https://docs.goauthentik.io/customize/blueprints/).
  - Discovery re-applies a blueprint only when its SHA-512 differs from `last_applied_hash` (`authentik/blueprints/v1/tasks.py` lines 144 and 185). An unchanged file is not re-applied on restart.
  - `blueprints.goauthentik.io/instantiate: "false"` opts a file out (tasks.py line 168).
  - Order between files is not guaranteed. Use `!KeyOf`/`!Find` and meta models for dependencies (https://docs.goauthentik.io/customize/blueprints/).
- **Idempotency:**
  - Entries are matched on `identifiers`. With `state: present` (the default), a match updates only the `attrs` fields, so re-applying does not duplicate anything (https://docs.goauthentik.io/customize/blueprints/v1/structure/).
  - `created` creates the object once and never overwrites manual edits.
  - `must_created` fails if the object already exists.
  - `absent` deletes the object (same page).
  - Each blueprint applies in one atomic transaction and rolls back as a whole on any error (https://docs.goauthentik.io/customize/blueprints/).
  - Removing a file deletes its instance but "the objects they created are not affected" (same page). Deletions must be explicit `state: absent` entries.
- **What a blueprint can declare** (model list from `blueprints/schema.json` at 2026.8.5):
  - **flows** `authentik_flows.flow`. Its `layout` enum is `stacked`, `content_left`, `content_right`, `sidebar_left`, `sidebar_right`, `sidebar_left_frame_background`, `sidebar_right_frame_background` (schema.json lines 10601-10613).
  - **stage bindings** `authentik_flows.flowstagebinding`.
  - **stages:**
    - `authentik_stages_identification.identificationstage`, `…password.passwordstage`, `…prompt.prompt` / `promptstage`
    - `…authenticator_sms.authenticatorsmsstage`, `…authenticator_validate.authenticatorvalidatestage`
    - `…user_write.userwritestage`, `…user_login.userloginstage`, `…user_logout.userlogoutstage`
    - `…email.emailstage`, `…deny.denystage`, `…redirect.redirectstage`, `…captcha.captchastage`
    - and the rest.
  - **policies:** expression, password, reputation, expiry, event-matcher, unique-password, plus `authentik_policies.policybinding`.
  - **groups** `authentik_core.group`, **users** `authentik_core.user`, **roles** `authentik_rbac.role`, **tokens** `authentik_core.token`.
  - **providers / applications** `authentik_providers_oauth2.oauth2provider`, `authentik_core.application`.
  - **property mappings** `authentik_providers_oauth2.scopemapping`. This is where the `roles`, `tenant_id` and `party_id` claims would be built.
  - **notifications / webhooks** `authentik_events.notificationtransport`, `notificationrule`, `notificationwebhookmapping`.
  - **branding** `authentik_brands.brand`. Its fields include `branding_title`, `branding_logo`, `branding_favicon`, `branding_custom_css`, `branding_default_flow_background`, `attributes`, and default flows `flow_authentication`, `flow_recovery`, `flow_invalidation`, `flow_user_settings`, `flow_unenrollment` and others (schema.json from line 5436).
  - So every category in the ticket (flows, stages, groups, providers, applications, property mappings, branding) is declarable.
- **Secrets:**
  - `!Env my_env_var` (with an optional default) "Returns the value of the given environment variable".
  - `!File /path` returns a file's contents (https://docs.goauthentik.io/customize/blueprints/v1/tags/).
  - Because blueprints run in the worker, **the variables must be set on the worker container**. This follows from index.mdx line 23 and the tasks being worker tasks. **UNCONFIRMED** as an explicit docs statement.
  - Blueprint-only fields allow setting a user `password` or `password_hash`, and a token `key` (https://docs.goauthentik.io/customize/blueprints/v1/models/).
  - Today's `LP_*_URL` / `PASSWORD_RESET_SMS_SECRET` values would arrive this way, e.g. as a webhook transport URL and a shared-secret header.
  - Config values themselves also accept `env://NAME` and `file://NAME` references (https://docs.goauthentik.io/install-config/configuration/, "About authentik configurations").

## 5. Languages: Swahili (`sw`)

- **Not supported in 2026.8.5.**
  - The frontend's `web/lit-localize.json` `targetLocales` are: ar, bg-BG, cs-CZ, de-DE, en, en-XA, es-ES, fi-FI, fr-FR, it-IT, ja-JP, ko-KR, nb-NO, nl-NL, pl-PL, pt-BR, ru-RU, tr-TR, zh-Hans, zh-Hant (https://github.com/goauthentik/authentik/blob/version/2026.8.5/web/lit-localize.json, lines 4-25). There is no `sw`.
  - `web/xliff/` and the backend `locale/` directories have no `sw` entry either. I checked the GitHub contents listings of both at the tag. The whole repository tree at the tag has no `sw` locale file.
- **How the UI picks a language** (`web/src/common/ui/locale/utils.ts` lines 182-200), in this order:
  1. the `?locale=` URL parameter
  2. the session-persisted locale
  3. a hint (brand or user `settings.locale`)
  4. `navigator` language
  5. a fallback
  6. English
- `findSupportedLocale` returns only compiled-in locales (same file, around line 115). A Swahili browser, or `settings.locale: sw` on the brand (website/docs/customize/branding/index.mdx lines 67-76), therefore **falls back to English**.
- **Can translations be added?** Only by building our own image:
  - The documented route adds the tag to `web/lit-localize.json` `targetLocales`, adds a `LocaleLoaderRecord` entry in `web/src/common/ui/locale/definitions.ts`, and supplies a `web/xliff/<tag>.xlf` catalog.
  - Backend strings need a Django catalog (`ak makemessages -l …` / `compilemessages`).
  - Upstream contributions go through Transifex (https://docs.goauthentik.io/developer-docs/translation/).
  - The frontend bundle is compiled, so a stock image cannot pick up a new locale at runtime. The translation guide describes no runtime override. **UNCONFIRMED** that none exists, but I found none.
- **Admin-entered strings are single-language:** flow titles, prompt labels, and the brand-panel statement that today's theme carries in `messages_sw.properties`. A flow has one `title` field (https://docs.goauthentik.io/add-secure-apps/flows-stages/flow/, "Title"). **UNCONFIRMED** that no per-locale variant exists; I found none.
- **Unaffected:** the SMS wording itself. Texts go Authentik → backend webhook → the existing gateway, so the Swahili text bodies stay in the backend.

## 6. Theming limits (today: `backend/keycloak/themes/lifeplatform/login`)

Today's theme is FreeMarker markup we own:
- `template.ftl` and `login.ftl`
- a two-panel "split leaf" shell: deep-green brand panel with photo and display copy, white form panel (`resources/css/login.css`)
- its own locale dropdown, an `en,sw` locale list and translated strings (`theme.properties`)

What authentik lets us change:

- **Brand settings:** branding title, logo, favicon, default flow background image, custom CSS (website/docs/customize/branding/index.mdx lines 17-27, https://docs.goauthentik.io/customize/branding/). A per-flow background overrides the brand default (https://docs.goauthentik.io/customize/interfaces/flow/).
- **Layout:** chosen from a fixed set: stacked, content left/right, sidebar left/right (if-flow.mdx; https://docs.goauthentik.io/customize/interfaces/flow/).
  - The 2026.8.5 schema and `FlowExecutor.ts` (lines 392-410) add `sidebar_*_frame_background`. In those layouts the background URL is rendered in an `<iframe part="content-iframe">`, which could in principle carry an HTML brand panel. **UNCONFIRMED:** not described in the docs.
  - "Sidebar left/right" with the brand photo as background is the closest match to today's split panel.
- **Custom CSS:**
  - Applies to the flow executor (login and enrollment pages) and the user library (https://docs.goauthentik.io/brands/custom-css/, source custom-css.mdx lines 6-9).
  - The supported hooks are CSS variables (`--ak-*`, PatternFly `--pf-*`) and `::part()` selectors.
  - The executor exposes `login`, `main`, `branding`, `footer`, `content`, `content-iframe`, `locale-select` (+ `locale-select-label` / `-select`) (FlowExecutor.ts lines 400-446).
  - The locale selector can be hidden with `ak-flow-executor::part(locale-select){display:none}` (custom-css.mdx lines 183-191).
  - Colours, typography, radii, spacing and backgrounds are reachable. The green palette and pill buttons are realistic.
  - "Compatibility mode" renders a light DOM instead of shadow DOM, so ordinary selectors reach further (flow docs, "Compatibility mode").
- **What it cannot do:**
  - **Change markup or text.** There is no template override for flow pages; `/templates` is email-only (architecture page). Today's brand-panel statement and body copy have no slot. The only hack is CSS `content:`, which cannot be localized because `sw` is not a locale.
  - **Promise stability.** "Direct element styling tends to break when authentik updates … especially for complex pages like the flow executor. CSS parts are more limited in scope but provide a stable theming API" (custom-css.mdx lines 208-212). "There's currently no comprehensive documentation on available CSS variables and parts" (lines 223-227).
  - **Add custom JavaScript.** Today's `login.js` / `passwordVisibility.js` behaviours would be lost; authentik's own password field has its own reveal. **UNCONFIRMED:** I found no documented custom-JS hook.
- **Recovery from a broken theme:** a recovery token disables custom CSS (`ak create_recovery_key`, custom-css.mdx lines 233-241). 2026.8 added "Recovery Token CSS Safe Mode" (2026.8 release notes).

## 7. Bootstrapping the first admin non-interactively

- **Worker** environment variables, "Only read on the first startup" (website/docs/install-config/automated-install.mdx, https://docs.goauthentik.io/install-config/automated-install/):
  - `AUTHENTIK_BOOTSTRAP_PASSWORD_HASH`: a Django hash. Generate it with `printf '%s' "$PASSWORD" | docker compose run --rm server hash_password`. Escape `$` as `$$` inline in compose.
  - `AUTHENTIK_BOOTSTRAP_PASSWORD`: plaintext, discouraged. Setting both variables is an error.
  - `AUTHENTIK_BOOTSTRAP_TOKEN`: an API token key for `akadmin`.
  - `AUTHENTIK_BOOTSTRAP_EMAIL` is optional. Leave it unset, because email is never a channel here.
- These "can't be defined using the file-based syntax (`file://`)", so they cannot be Docker secrets in compose (same page). They come from `.env` like today's `KC_ADMIN_PASSWORD`.
- **Required secrets** in any case: `AUTHENTIK_SECRET_KEY` and the Postgres password (docker-compose install page; compose.yml line 33).
- **Alternative or complement:** a blueprint can create or update a named admin user with `password: !Env …` (blueprint models page). Blueprints re-apply only on file change, which suits dev seeding.
- Without bootstrap variables, first boot shows the interactive `/if/flow/initial-setup/` page (docker-compose install page, "Access authentik").

## Sketch of the two compose changes (for the spec, not built)

- **Dev** (`backend/infra/docker-compose.yml`):
  - Replace the `keycloak` service with `authentik-server` and `authentik-worker`, both `ghcr.io/goauthentik/server:2026.8.5`.
  - `AUTHENTIK_POSTGRESQL__HOST=postgres`, `__NAME=authentik`.
  - `AUTHENTIK_SECRET_KEY` and `AUTHENTIK_BOOTSTRAP_PASSWORD` in `.env`.
  - Port `8081:9000`.
  - `../authentik/blueprints:/blueprints/lifeplatform:ro` on the worker.
  - `../authentik/media:/data` for the brand photo, or external URLs.
  - No docker socket, no Redis dependency.
  - The backend's `depends_on` names the server.
- **Server** (`deploy/server/compose.yml`):
  - The same two services, using the stock image unless Swahili forces a custom build.
  - Drop `keycloak-config`.
  - `127.0.0.1:8081:9000` for the admin UI.
  - A healthcheck: **UNCONFIRMED** which command. The shipped compose has none for server/worker, so a healthcheck command must be verified in the build.
  - `AUTHENTIK_LISTEN__TRUSTED_PROXY_CIDRS` checked against Caddy's network.

## Verdict

- **Runnable: yes, and lighter than today.**
  - 2026.8.5 needs two containers (server and worker) from one stock image. Redis has not been needed since 2025.10.
  - It can sit in its own `authentik` database on our existing Postgres 16, which meets the ≥ 14 requirement.
  - The cost: roughly 50% more DB connections than older releases, which needs headroom in `max_connections`.
- **RAM on the 7.8 GB dev box: fits.**
  - There is no published idle figure (UNCONFIRMED).
  - The project's own floor is 2 GB for the whole stack including Postgres, and its Helm example sizes each container at 512 MiB.
  - That puts server plus worker at about 1 GB, against Keycloak's documented ~1.25 GB base. `keycloak-config` also goes, and no Redis or second Postgres is added.
  - Expect roughly neutral memory. Set `mem_limit` on both containers and measure with `docker stats` early in the cut-over branch.
- **Blueprints: fully adequate.**
  - They are mounted on the worker under `/blueprints/<sub>`.
  - They are applied on startup and on file change, and idempotent by `identifiers` + `state: present`.
  - They are atomic per file, can declare every object type we need including the brand and its CSS, and take secrets via `!Env` / `!File`.
- **Swahili: the blocking finding.**
  - authentik 2026.8.5 ships **no `sw` locale** in either the frontend or the backend catalogs. A Swahili user or brand silently gets English.
  - Adding it means one of two things:
    - maintaining a **custom-built image**: patch `lit-localize.json` and `definitions.ts`, add `sw.xlf` plus a Django `sw` catalog, and rebuild on every upgrade
    - getting Swahili accepted upstream through Transifex
  - Admin-entered strings (flow titles, prompt labels, brand copy) are single-language either way.
  - The map's requirement "password reset … in English and Swahili" therefore **cannot be met with the stock image**. Under the map's rule this must become its own ticket, never a silent drop. The SMS text bodies are unaffected because they stay in the backend.
- **Theming: "same feel", not the same markup.**
  - Achievable: logo, favicon, title, background photo, sidebar layout, and colours and fonts via CSS variables and `::part`.
  - Not achievable: custom markup, the brand-panel display copy, custom JS, or a stable contract for anything beyond parts and variables.
  - This fits the map's "small differences accepted".
- **Admin bootstrap: solved.** Use `AUTHENTIK_BOOTSTRAP_PASSWORD(_HASH)` / `_TOKEN` on the worker, read on first start only, from `.env` (not `file://`), or a blueprint user with `!Env`.
