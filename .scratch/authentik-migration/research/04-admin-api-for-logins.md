# 04 — What does Authentik's API offer for the backend's login operations?

Ticket: [../issues/04-admin-api-for-logins.md](../issues/04-admin-api-for-logins.md)
Researched 2026-11-19.

**Release researched: Authentik `2026.8.5`**, the latest GitHub release at the time of writing (tag `version/2026.8.5`, published 2026-10-21; https://api.github.com/repos/goauthentik/authentik/releases/latest). Every source citation below is pinned to that tag:

- `SRC/...` means `https://github.com/goauthentik/authentik/blob/version/2026.8.5/...`
- `schema.yml:N` means `https://github.com/goauthentik/authentik/blob/version/2026.8.5/schema.yml#LN` (its `info.version` is `2026.8.5`, line 4)

All API paths are relative to `/api/v3`.

---

## 1. Inventory: what the three adapters do today

All three adapters authenticate the same way. They POST `client_credentials` to `/realms/{realm}/protocol/openid-connect/token` as the `lifeplatform-admin` confidential client, which holds `manage-users` and `view-users` only. They cache the token until about 30 s before it expires:

- `KeycloakStaffLogins.java:166-187`
- `KeycloakAgentLogins.java:201-224`
- `KeycloakCustomerAccounts.java:199-222`

File paths:

- Staff: `backend/src/main/java/tz/co/nlolo/lifeplatform/iam/infrastructure/KeycloakStaffLogins.java`
- Agent: `backend/src/main/java/tz/co/nlolo/lifeplatform/distribution/infrastructure/KeycloakAgentLogins.java`
- Customer: `backend/src/main/java/tz/co/nlolo/lifeplatform/omnichannel/infrastructure/KeycloakCustomerAccounts.java`

Ports:

- `iam/application/StaffLogins.java`
- `distribution/application/AgentLogins.java`
- `omnichannel/application/CustomerAccounts.java`

Each port has four methods: `create`, `sendSetPasswordLink`, `setTemporaryPassword` and `setEnabled`.

| # | Operation | Keycloak call | Where (file:line) |
|---|---|---|---|
| K0 | Get an admin token | `POST /realms/{r}/protocol/openid-connect/token` (client_credentials) | Staff 175; Agent 212; Customer 210 |
| K1 | Create a user: `username`, `enabled:true`, `firstName` (plus `lastName` for staff), optional `email` (`emailVerified:true` for staff and agent), `requiredActions:[UPDATE_PASSWORD]` | `POST /admin/realms/{r}/users`, then the id is read from the `Location` header | Staff 69-90; Agent 63-84; Customer 63-83 |
| K2 | Set attributes at creation: `tenant_id` (all three), `party_id` (agent and customer), `home_branch` (staff, optional) | part of K1's body | Staff 79-82; Agent 72; Customer 71 |
| K3 | Detect a duplicate (409) | K1 returns 409 | Staff 101-103 refuses with `USERNAME_TAKEN`; Agent 86-94 and Customer 85-93 join instead |
| K4 | Look up a user by exact username | `GET /admin/realms/{r}/users?username=…&exact=true` | Agent 110-112; Customer 109-111 |
| K5 | Look up a user by exact email | `GET …/users?email=…&exact=true` | Agent 114-118; Customer 113-117 |
| K6 | Read the `party_id` attribute of a found user, so the adapter can join it or refuse | client-side | Agent 120-127; Customer 119-126 |
| K7 | Grant a workspace realm role (`staff`, `agent` or `customer`). The adapter lists available roles, then posts the mapping | `GET …/users/{id}/role-mappings/realm/available`, then `POST …/role-mappings/realm` | Staff 153-163; Agent 172-190; Customer 169-187 |
| K8 | Revoke a workspace realm role. The adapter lists assigned roles, then deletes the mapping | `GET …/role-mappings/realm`, then `DELETE …/role-mappings/realm` | Agent 192-198; Customer 189-195 |
| K9 | Delete a user (staff rollback when K7 fails) | `DELETE /admin/realms/{r}/users/{id}` | Staff 95-96 |
| K10 | Send a "set your password" link **by email**, 72 h lifespan, redirecting to the console or portal | `PUT …/users/{id}/execute-actions-email?client_id&redirect_uri&lifespan=259200` with body `["UPDATE_PASSWORD"]` | Staff 110-119; Agent 131-142; Customer 130-139 |
| K11 | Set a temporary password that must be changed at first sign-in | `PUT …/users/{id}/reset-password` with `{type:password, value, temporary:true}` | Staff 123-130; Agent 145-152; Customer 143-150 |
| K12 | Disable or enable the whole account (staff) | `PUT …/users/{id}` with `{enabled}` | Staff 134-138 |
| K13 | Sign out everywhere | `POST …/users/{id}/logout` | Staff 140-141 (on disable); Agent 163-164 and Customer 161-162 (on workspace close) |

Notes from the callers:

- **K10 is only reached when email is on.** The callers are `AccessAdministration.java:354-356`, `AgentLoginService.java:202-204` and `PortalAccessService.java:165-167`. Email is never a channel, so in practice every first password today is K11: a temporary password texted by the backend.
- **Usernames are phone numbers in `+255…` form.** See `AgentLoginService.usernameFor` (lines 193-198), `PortalAccessService.usernameFor` (lines 157-162) and `party/api/PhoneNumbers.java:34-43`.
- **How the platform stores the IdP id:**
  - Staff store it as a **UUID**: `iam.staff_user.user_id UUID`, in `db-migrations/iam/V1__access_control.sql:42`. It is matched against the JWT `sub` (`AccessExpressions.java:91`).
  - Agents and customers store it as `VARCHAR(64)`: `db-migrations/distribution/V8__agent_login.sql:18` and `db-migrations/omnichannel/V1__portal_access.sql:19`.

---

## 2. The Authentik side: building blocks

### 2.1 The user object and its id

- `UserSerializer` exposes these fields (`SRC/authentik/core/api/users.py:336-356`):
  - `pk`, an **integer** that is the API path id
  - `uuid`, a separate UUID
  - `username`, `name`, `is_active`, `email`, `attributes`, `groups`, `roles`, `path`, `type`
- `username` and `name` are required: `schema.yml:61218-61220`.
- Every user endpoint is addressed by the integer `pk`: `/core/users/{id}/` at `schema.yml:4757`.
- The OIDC `sub` claim is configurable per provider through `sub_mode`. The modes are `hashed_user_id` (the default), `user_id`, `user_uuid`, `user_username`, `user_email` and `user_upn`. See `schema.yml:59408-59416` and `SRC/authentik/providers/oauth2/models.py:307-309`.

The staff table holds a UUID matched against `sub`. That means:

- The provider must use `sub_mode: user_uuid`.
- The adapters must resolve uuid → pk with `GET /core/users/?uuid=<uuid>`. The `uuid` filter is declared at `users.py:557` and appears at `schema.yml` under `/core/users/` (the `uuid` query parameter).
- Alternatively, store both ids. **Workaround, not a gap.**

### 2.2 Usernames can be phone numbers

- **Model:** `User` extends Django's `AbstractUser` (`SRC/authentik/core/models.py:365`). Django's own username validator allows `[\w.@+-]`, so a `+` is allowed.
- **API:** `UserSerializer` redeclares `username` as a plain `CharField(max_length=150)` with only a `UniqueValidator`, at `users.py:159-162` (and `models.py:60` for `USERNAME_MAX_LENGTH = 150`). No regex is applied.

`+255712345678` is therefore valid. **Clean.**

- Uniqueness is the Django unique constraint, which is case-sensitive. That doesn't matter for phone numbers.
- **Email is not unique** in Authentik, because `AbstractUser.email` is not unique. Keycloak's "email already exists" conflict (K5's reason to exist) therefore goes away.

### 2.3 How a non-superuser API client is limited

**Authenticating.** The API accepts `Authorization: Bearer <key>` where `<key>` is either of these (`SRC/authentik/api/authentication.py:111-135`; docs at https://api.goauthentik.io/authentication/):

- a `Token` with `intent=api`
- an OAuth access token that carries the scope `goauthentik.io/api`

The request then runs **as the token's owner** (`return key_token.user, key_token`), so the owner's RBAC permissions apply.

The simplest setup is a static API token owned by a `service_account`-type user, created in a blueprint:

- A blueprint may set `key` explicitly (`SRC/authentik/core/api/tokens.py:38-42`), so the key can come from an env secret.
- Set `expiring: false`. An expiring API token gets **its key rotated** when it expires (`SRC/authentik/core/models.py:1210-1232`). Its expiry date can't be chosen either: it is always the default token duration (`tokens.py:87-89`). An expiring token would therefore silently break the backend.

There is no need for a `client_credentials` round-trip like K0. If one is wanted anyway, client_credentials is documented with username + app password (https://docs.goauthentik.io/add-secure-apps/providers/oauth2/client_credentials/), and the scope `goauthentik.io/api` must be requested for API use (UNCONFIRMED in the client_credentials docs page; the scope check is in `authentication.py:126-133`).

**Permissions.** Access control is fully role-based since 2025.12. Permissions are assigned to roles, and roles to users or groups. There are global and object permissions. See https://docs.goauthentik.io/users-sources/access-control/permissions/.

- The default DRF permission class is `ObjectPermissions` (`SRC/authentik/root/settings.py:248`; `SRC/authentik/rbac/permissions.py:15-49`).
  - Create needs global `add_user`.
  - Update and delete need `change_user` or `delete_user`, either globally or on that object.
  - Lists are filtered by `view_user` through `ObjectFilter` (settings.py:244).
- Custom actions use `@permission_required(obj_perm, global_perms)`, which checks the global permission first and then the object permission (`SRC/authentik/rbac/decorators.py:14-44`).

**Initial permissions** can scope the service account to the users it creates itself. An `InitialPermissions` object bound to the service account's role grants listed object permissions on every object a member of that role creates. Docs: https://docs.goauthentik.io/users-sources/access-control/initial_permissions/. Authentik's own test shows this happens for creation through the API (`SRC/authentik/rbac/tests/test_initial_permissions.py:62-71`); `assign_initial_permissions` is at `rbac/permissions.py:65-79`.

This matters for security. A **global** `reset_user_password` or `change_user` would let a leaked backend token reset `akadmin` and take over the whole IdP. Keycloak's `manage-users` in the `lifeplatform` realm could not reach the master-realm admin.

Recommended role for the backend service account:

| Permission | Scope | Needed for |
|---|---|---|
| `authentik_core.add_user` | global | create |
| `authentik_core.view_user` | global, or initial (object) | look up the user and join |
| `authentik_core.change_user` | initial (object, on users it creates) | deactivate, attributes |
| `authentik_core.delete_user` | initial (object) | staff rollback |
| `authentik_core.reset_user_password` | initial (object) | `set_password`, `recovery` |
| `authentik_core.view_group` + `add_user_to_group` + `remove_user_from_group` | object, on the three workspace groups only | workspace grant and revoke |
| `authentik_core.delete_authenticatedsession` | **global** (see §3, K13) | sign-out on workspace close |
| `authentik_providers_oauth2.view_refreshtoken`, `delete_refreshtoken` | global (UNCONFIRMED codenames) | sign-out on workspace close |

A caveat on object-only `view_user`: users created by a blueprint (the dev seed) are not visible to the backend. Because of the "dev data only, seeded fresh" decision, the seeder should create logins through the backend, or the backend role keeps global `view_user`, which is low risk.

### 2.4 "Choose your password" link: recovery link, not invitation

- **Recovery link.** `POST /core/users/{id}/recovery/` with body `{token_duration?: "days=3"}` returns `{"link": "…/if/flow/<recovery-flow>/?flow_token=…"}`. It needs `reset_user_password` on the user. Source: `users.py:972-986`; schema: `schema.yml:4898`.
  - The plan behind the link is pre-planned with `pending_user` set, so the user doesn't identify themselves.
  - The token is revoked on use (`revoke_on_execution`).
  - There is one live token per user (`identifier=f"{uid}-password-reset"`, `update_or_create`). Source: `users.py:729-776`.
  - The default lifetime is the tenant's `default_token_duration` (`models.py:95-103`).
  - Docs: https://docs.goauthentik.io/users-sources/user/user_basic_operations/ ("Create recovery link"; "this email stage is not required and can be removed").
- **Recovery email.** `/recovery_email/` (`users.py:988-1028`) needs an email stage and an email address. It is **not usable**, because email is never a channel.
- **Invitations.** These are for *enrolment* flows that **create** the user (`fixed_data`, `single_use`, an optional `flow`), in `SRC/authentik/stages/invitation/models.py:50-73`. The backend creates the user itself, with attributes and groups, before any link exists, so invitations don't fit. The recovery link does.

Three constraints on the recovery link (**workarounds**):

1. **It is always the brand's recovery flow.** `_create_recovery_link` uses `self.request.brand.flow_recovery` and takes no flow parameter (`users.py:733-737`). The "set your first password" link and self-service SMS reset (ticket 02) therefore share one flow. That flow's policies must allow these links while still refusing self-reset for staff.
2. **The brand and the host in the link come from the API request's `Host` header.**
   - The brand is matched on `request.get_host()` (`SRC/authentik/brands/utils.py:29-49`).
   - The link is built with `build_absolute_uri` (`users.py:771-774`).
   - A backend calling `http://authentik:9000` gets the default brand and an **internal hostname in the link**. The adapter must send the public `Host` header (or rewrite the link).
3. **The link comes back to the caller and is not sent anywhere**, which is what we want: the backend texts it, as the map requires.

---

## 3. Operation by operation

**K0 — get an admin token.**
- Authentik: a static `Token` (`intent=api`, `expiring=false`, key from env) owned by a service-account user (§2.3). There is no token call at all.
- Fields: header `Authorization: Bearer <key>`.
- **Clean, and simpler** than K0.

**K1 + K2 — create a user with attributes.**
- `POST /core/users/` with `{username, name, is_active:true, attributes:{tenant_id, party_id | home_branch}, groups:[<workspace group uuid>], path?, type:"internal"}`.
  - Schema: `schema.yml:4571` (the `post` operation under `/core/users/`); serializer `users.py:138-362`.
  - The response is the user object with `pk` and `uuid`, so no `Location` parsing is needed.
  - `email` is optional and left out.
- `requiredActions` has no equivalent (see K11).
- Workspace groups can be set **in the same create call**. `validate_groups` only checks for superuser groups (`users.py:292-308`), so `add_user_to_group` is not demanded on create (observed in source; not documented). This makes staff creation atomic and removes the need for the K9 rollback.
- **Clean.**

**K3 — duplicate username.**
- `UniqueValidator` returns **400** with a field error on `username` (`users.py:159-162`), not 409.
- The adapters must map a 400 that has a `username` error to today's 409 handling.
- **Workaround** (trivial).

**K4 — look up by exact username.**
- `GET /core/users/?username=<u>`, an exact match through the django-filter `Meta.fields` (`users.py:617-631`), parameter `username` at `schema.yml` under `/core/users/`.
- Results are paginated (`results[]`).
- **Clean.**

**K5 — look up by exact email.**
- `GET /core/users/?email=<e>` exists, but email is not unique, so the "conflict on the address alone" case K5 handled can't happen.
- Drop it. **Clean (no longer needed).**

**K6 — read `party_id` of the found user.**
- The `attributes` dict comes back on the user object.
- A direct filter also exists: `GET /core/users/?attributes={"party_id":"…"}` (`users.py:598-614`).
- **Clean.**

**K7 — grant a workspace role.**
- Model the workspaces `staff`, `agent` and `customer` as **groups**, and emit them in the `roles` claim with a scope mapping. The user's groups are available as `request.user` in expressions (https://docs.goauthentik.io/add-secure-apps/providers/property-mappings/expression/); `User.all_groups()` is at `models.py:416-418`. The exact expression is ticket-owned elsewhere.
- Grant: `POST /core/groups/{group_uuid}/add_user/` with body `{pk}` (`schema.yml:3912-3943`, `UserAccountRequest` at `schema.yml:60533-60540`). It needs `add_user_to_group` on the group and `view_user` on the user (`SRC/authentik/core/api/groups.py:487-524`).
- It is idempotent: adding an existing member is a no-op `add`.
- This replaces the list-available-then-post dance.
- **Clean.**
- Alternatives:
  - Authentik RBAC `roles` (`/rbac/roles/{uuid}/add_user/`) are for Authentik's own admin permissions, and assigning them needs `authentik_rbac.change_role` (`users.py:310-321`). Don't use them for workspaces.
  - Storing workspaces in `attributes` would need the full read-merge-write described under K11.

**K8 — revoke a workspace role.**
- `POST /core/groups/{group_uuid}/remove_user/` with `{pk}` (`schema.yml:3944-3975`; `groups.py:526-560`). It needs `remove_user_from_group`.
- **Clean.**

**K9 — delete a user.**
- `DELETE /core/users/{id}/` (`schema.yml:4757`, the `delete` operation). It needs `delete_user` on the object.
- **Clean.** It is probably not needed once K1 sets groups at create.

**K10 — set-your-password link by email.**
- The equivalent is `POST /core/users/{id}/recovery/` → `{link}`, and the backend sends it (§2.4).
- Lifespan: `token_duration: "days=3"`, matching today's 259200 s.
- The `redirect_uri` back to the console or portal has no parameter. Where the user lands after the flow is a flow setting, plus the `next` query on the link; whether `next` survives with a `flow_token` is UNCONFIRMED.
- The brand-flow and `Host` caveats in §2.4 apply.
- **Workaround.** It is also dead code today (email is off), so this matters only if the "link" delivery is kept, for example texting the link instead of a password.

**K11 — temporary password that must be changed at first sign-in.**
- `POST /core/users/{id}/set_password/` with `{password}` sets a **permanent** password (`users.py:911-935`; `schema.yml:4960`). It needs `reset_user_password`. There is **no `temporary` flag and no required actions** in Authentik.
- Authentik documents a workaround (https://docs.goauthentik.io/users-sources/user/password_reset_on_login/):
  - a user attribute `reset_password: true`
  - an expression policy `reset_password_check` on a Prompt stage with the password-change fields
  - an expression policy `reset_password_update` on a User Write stage that clears the flag
  - both stages bound into the authentication flow between the password stage and the MFA stage
- The adapter therefore makes three calls: `set_password`, then `GET /core/users/{id}/`, then `PATCH /core/users/{id}/` with the **whole** `attributes` dict plus `reset_password: true`.
  - DRF replaces `attributes` wholesale. `AttributesMixinSerializer` validates but does not merge (`SRC/authentik/core/api/object_attributes.py:14-26`).
  - So omitting `tenant_id` or `party_id` from the PATCH would erase them.
- **Side effect:** `User.set_password` fires `password_changed`, which writes a `PASSWORD_SET` event (`models.py:562-570`; `SRC/authentik/events/signals.py:121-130`). If ticket 03's "your password changed" text is driven by that event, an administrator-issued temporary password also triggers that text. The webhook will need to tell the cases apart, for example by the acting user being the service account (UNCONFIRMED what the event context carries).
- **Workaround (documented pattern, but flow configuration, not an API flag).**

**K12 — disable or enable an account.**
- `PATCH /core/users/{id}/` with `{is_active}` (`schema.yml:4757`, the `patch` operation). It needs `change_user`.
- Deactivating **automatically**:
  - deletes all of the user's sessions (`SRC/authentik/core/signals.py:113-120`)
  - deletes all OAuth access, refresh and device tokens (`SRC/authentik/providers/oauth2/signals.py:121-130`)
- Docs: "The active sessions are revoked and the authentication of the user blocked" (https://docs.goauthentik.io/users-sources/user/user_basic_operations/).
- **Clean.** It also covers K13 for staff.

**K13 — sign out everywhere while the account stays active (agent or customer workspace closed).**
- There is no per-user logout action on `/core/users/`.
- The closest equivalent is `DELETE /core/authenticated_sessions/bulk_delete/?user_pks=<pk>` (`schema.yml:3353-3380`). It needs **global** `delete_authenticatedsession`: the decorator's object path can't resolve an object on a list action (`SRC/authentik/core/api/authenticated_sessions.py:142-154`; `decorators.py:17-25`).
- Deleting sessions also deletes the session-bound **access** tokens (`oauth2/signals.py:89-118`). **Refresh tokens deliberately survive** session deletion (`on_delete=SET_DEFAULT`, with the comment "we want refresh tokens to persist even when the session is terminated", at `SRC/authentik/providers/oauth2/models.py:686-691`).
- For parity with Keycloak's logout, the adapter must also list and delete the user's refresh tokens: `GET /oauth2/refresh_tokens/?user=<pk>`, then `DELETE /oauth2/refresh_tokens/{id}/` (`schema.yml:10583-10662`). Alternatively, rely on the next refresh dropping the revoked group from `roles`; whether refresh re-runs scope mappings is UNCONFIRMED.
- A global session-delete permission lets the token sign anyone out, but not take over an account.
- **Workaround** (two or three calls, and a global permission).

---

## 4. Other findings that bind the spec

- **The id type changes.** Use the UUID everywhere (it matches `sub` under `user_uuid` mode), resolving pk per call or caching it. Or store the pk alongside it in the two `VARCHAR(64)` columns and `staff_user`. Neither needs a schema change if the UUID is what gets stored.
- **The admin API must be reached with the public Host**, or brand-bound actions (`recovery`) pick the default brand and leak an internal URL (§2.4).
- **Optional:** Authentik's `ObjectAttribute` (`is_unique`, `is_required`, `regex` per attribute key, in `object_attributes.py:46-80`) could make `tenant_id` required on users. It isn't needed.
- **Expiring API tokens rotate their key on expiry.** The blueprint must set `expiring: false` (`models.py:1210-1232`).

---

## Verdict

| Operation (code) | Authentik endpoint | Verdict |
|---|---|---|
| K0 admin token (client_credentials) | static `Token` intent `api`, `expiring:false`, owned by a service account; `Authorization: Bearer` | **clean** (simpler) |
| K1 create user | `POST /core/users/` `{username, name, is_active, attributes, groups}` | **clean** |
| K2 `tenant_id` / `party_id` / `home_branch` attributes | `attributes` on the same create | **clean** |
| K3 duplicate → 409 | 400 with a `username` field error | **workaround** (map 400 → taken) |
| K4 look up by username | `GET /core/users/?username=` | **clean** |
| K5 look up by email | not needed (email not unique); `?email=` exists | **clean** (drop it) |
| K6 read `party_id` | user `attributes`, or `?attributes={"party_id":…}` | **clean** |
| K7 grant workspace | `POST /core/groups/{uuid}/add_user/ {pk}` (or `groups` at create) | **clean** |
| K8 revoke workspace | `POST /core/groups/{uuid}/remove_user/ {pk}` | **clean** |
| K9 delete user (rollback) | `DELETE /core/users/{id}/` | **clean** |
| K10 set-your-password link | `POST /core/users/{id}/recovery/ {token_duration}` → `{link}`, then the backend texts it | **workaround** (brand's recovery flow only; public `Host` header needed; landing URL UNCONFIRMED) |
| K11 temporary password, change at first sign-in | `POST /core/users/{id}/set_password/` + `PATCH` attribute `reset_password:true` + policy-bound prompt and user-write stages in the auth flow | **workaround** (no `temporary` flag; full-attributes PATCH; fires `PASSWORD_SET`) |
| K12 disable / enable account | `PATCH /core/users/{id}/ {is_active}`, which kills sessions and tokens itself | **clean** |
| K13 sign out everywhere, account stays active | `DELETE /core/authenticated_sessions/bulk_delete/?user_pks=` + delete `/oauth2/refresh_tokens/` | **workaround** (refresh tokens survive session delete; global permission) |
| Rights limited to the backend's needs | RBAC role: global `add_user`, plus **initial permissions** for change, delete and reset on the users it creates, plus object permissions on the three groups | **clean** (better than global grants, which would expose `akadmin`) |
| Phone number as username | `+255…` passes (`CharField` max 150, unique; Django validator allows `+`) | **clean** |

**No operation is a hard gap.** Two need flow configuration rather than an API flag:

- K11, the forced password change
- K10, links tied to the brand's recovery flow

One needs extra calls: K13, sign-out while the account stays active.
