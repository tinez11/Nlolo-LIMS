# Research 03: Can Authentik tell the backend when a password changes?

Ticket: [03-password-changed-text](../issues/03-password-changed-text.md). Researched 2026-11-19.

**Release studied:** authentik **2026.8.5**, the latest GitHub release (published 2026-10-21; `https://api.github.com/repos/goauthentik/authentik/releases/latest`). All source links point at the tag `version/2026.8.5`. Below, `SRC` = `https://github.com/goauthentik/authentik/blob/version/2026.8.5/`.

Note: docs.goauthentik.io currently shows 2026.8 marked "Pre-release" in its version selector, even though GitHub lists 2026.8.5 as the latest stable release. Every claim here was checked against the 2026.8.5 source, so the label does not change any answer.

## Today's behaviour (repo)

- `backend/keycloak/extensions/.../PasswordChangedListener.java:36` acts only on `UPDATE_PASSWORD`. That event covers a change from the account menu, the new password at the end of a reset, and the first change after a temporary password. Admin resets are **deliberately ignored** (lines 15-19, 51-53) because the admin flow already texts a temporary password.
- `PasswordChangeNotifier.java:37-59` POSTs `{"tenantId","userId","partyId","language"}` asynchronously, fire-and-forget. It has a 3 s connect timeout, a 5 s request timeout, and **no retry**: a failure is only logged.
- It signs the request with HMAC-SHA256 in the `X-Reset-Timestamp` and `X-Reset-Signature` headers (`KeycloakSignature.java:17-18,39-40`), using the shared `password-reset.sms-secret`.
- `PasswordChangedController.java:45-74` (`/internal/password-changed`) checks the signature and freshness, then reads `tenantId`, `userId`, `partyId` and `language`. It answers 202 to every properly signed request.
- `PasswordChangedNotices.java:56-80` finds the phone (party record or staff record) and stamps the text with the time **at which the backend handles the request** (`ZonedDateTime.now(clock)`, line 58).

## 1. Which event fires, and can a webhook notification rule deliver it?

### The event: `password_set`

- `EventAction.PASSWORD_SET = "password_set"`: `SRC/authentik/events/models.py` line 100. The docs describe it as "A user sets their password." (https://docs.goauthentik.io/sys-mgmt/events/event-actions/).
- It is raised by one receiver bound to two signals, `password_changed` and `password_hash_changed`:

  ```python
  @receiver(password_hash_changed)
  @receiver(password_changed)
  def on_password_changed(sender, user, password=None, request=None, **_):
      Event.new(EventAction.PASSWORD_SET).from_http(request, user=user)
  ```

  Source: `SRC/authentik/events/signals.py` lines 120-130. The signals are declared in `SRC/authentik/core/signals.py` lines 27 and 29.
- `User.set_password()` sends `password_changed` whenever the user already exists and `signal=True` (the default). `User.set_password_from_hash()` sends `password_hash_changed` (`SRC/authentik/core/models.py` lines 562-587).
- An internal hash upgrade during `check_password` calls `set_password(..., signal=False)`, so it does **not** raise the event (same file, lines 591-600).

### Which paths raise it

| Path | Code | Fires `password_set`? |
|---|---|---|
| User changes their own password (user-settings / password-change flow) | `user_write` stage: `if key == "password": user.set_password(value, request=self.request)`, `SRC/authentik/stages/user_write/stage.py` lines 115-117 | Yes |
| Recovery flow (the new password is written by a `user_write` stage) | same `user_write` code path | Yes. UNCONFIRMED end to end for whatever SMS-recovery flow ticket 02/the reset ticket designs, but any flow that writes `password` through `user_write` takes this path. |
| Admin sets a password (admin UI or API `POST /api/v3/core/users/{id}/set_password/`) | `SRC/authentik/core/api/users.py` lines 925-934, calls `user.set_password(..., request=request)` | **Yes**. Unlike Keycloak, it is the same event as a self-change. |
| Admin or API sets a pre-hashed password (`set_password_hash`) | same file, lines 951-966, `set_password_from_hash(..., request=request)` | Yes |
| Blueprint creates or updates a user with `password` / `password_hash` | same file, lines 210-253 (`_set_password`, no `request`) | Yes, on update. On create too, since `set_password` runs after `super().create()` gives the user a pk. |

Consequences for us:

1. **One event for every path.** An admin reset is not a separate event type the way Keycloak's admin events are. If we keep today's rule ("an admin reset needs no notice"), we have to tell the paths apart by the event context. `Event.from_http` stores `context.http_request.path` (`SRC/authentik/events/models.py` lines 208-226), so an expression policy can exclude paths under `/api/v3/core/users/`. That covers admin resets and also passwords our backend sets through the API when it creates staff, agent and customer logins (today's `Keycloak*Logins` adapters). Without this filter, **creating a login through the API would text "your password was changed"**.
2. **The event names the person whose password changed.** `from_http(request, user=user)` overrides the request's user with the target user (lines 227-230). Then:
   - During impersonation, the event is recorded as the **original** (impersonating) user, with `on_behalf_of` holding the impersonated one (lines 233-241).
   - When the session holds a flow plan whose pending user differs, `event.user` becomes `{"authenticated_as": <target>, **<pending user>}` (lines 242-257).
   - So for self-service and recovery flows (pending user = target), `event.user.pk` is the target.
   - For an admin API call from a session that still holds a flow plan, the event could name the **admin** instead. UNCONFIRMED whether a finished login leaves `SESSION_KEY_PLAN` in the session. This case is filtered out by the admin-path exclusion above anyway.
3. **The event fires before the password is saved.** The signal is sent inside `set_password`, before `user.save()` (`core/models.py` lines 562-570; `api/users.py` lines 928-930). A save that then fails (the API catches `ValidationError` / `IntegrityError` and returns 400) still leaves a `password_set` event, so it could produce a spurious text. This is rare.

### Notification rule with a webhook transport

- **Matching.** A Notification Rule is a policy-binding target. For every event, a worker task evaluates each rule's bound policies in MODE_ANY. `empty_result = False`, so a rule with no policies never fires (`SRC/authentik/events/tasks.py` lines 26-29, 69-78).
  - An **Event Matcher policy** can match on `action`, `app`, `model`, `client_ip` and a `query` (`SRC/authentik/policies/event_matcher/models.py` lines 22-52, `passes` at line 71). The docs say "An event must match all configured fields in the policy." (https://docs.goauthentik.io/sys-mgmt/events/notifications/).
  - So: an Event Matcher with `action=password_set`, plus an expression policy for the path exclusion if we want it. (Bindings in MODE_ANY means *any* passing policy fires the rule. To AND them, use a single expression policy that checks both the action and the path. This is an inference from `PolicyEngineMode.MODE_ANY` at line 70.)
- **Destination.** Notifications are created only for a destination group and/or the event user: "Notifications are only created when a destination group is selected or Send notification to event user is enabled." (https://docs.goauthentik.io/sys-mgmt/events/notifications/). In code, `destination_users()` yields `User(pk=event.user["pk"])` when `destination_event_user` is set (`SRC/authentik/events/models.py` lines 697-719).
  - Set **`destination_event_user = true`** and leave the group empty. That sends exactly one webhook per event, with `notification.user` = the person whose password changed.
  - One task is enqueued per (transport × destination user). `send_once` stops after the first user (`tasks.py` lines 83-96).
- **Webhook transport.** `TransportMode.WEBHOOK` = "Generic Webhook" (`models.py` lines 347-353). It POSTs to `webhook_url` (`models.py` line 373, `send_webhook` lines 439-489). The docs say "This transport sends a POST request to the configured URL." (https://docs.goauthentik.io/sys-mgmt/events/transports/).
- **Blueprints.** Notification rules, transports, webhook mappings and policies are all `SerializerModel`s (`models.py` lines 356, 679, 759), so they can be declared in blueprints like other models. UNCONFIRMED: I did not open a blueprint example for `authentik_events.notificationtransport`.

**Answer:** yes. `password_set` + Event Matcher (or expression) policy + Notification Rule (`destination_event_user`) + Generic Webhook transport sends a POST to a backend endpoint for every password change, with config only.

## 2. What is in the webhook body, and can the backend trust it?

### Default body

`send_webhook` (`SRC/authentik/events/models.py` lines 439-449) builds:

```python
default_body = {
    "body": notification.body,            # event.summary -> context["message"] or f"{action}: {context}" (lines 310-314)
    "severity": notification.severity,
    "user_email": notification.user.email,
    "user_username": notification.user.username,
}
# plus, when the event has a user:
default_body["event_user_email"] = notification.event.user.get("email")
default_body["event_user_username"] = notification.event.user.get("username")
```

The docs list the same keys (https://docs.goauthentik.io/sys-mgmt/events/transports/). The default body has **no user pk, no attributes, and no event id or time**. The username here would be the phone number (our convention), but that alone is not enough: the backend needs `tenant_id`, `party_id` and the language.

### Custom body: the Webhook Body Mapping

- A transport can carry `webhook_mapping_body` and `webhook_mapping_headers`, both `NotificationWebhookMapping` property mappings (`models.py` lines 384-405, 759).
- When a body mapping is set, its Python return value **replaces** the default body (lines 451-458).
- The mapping is evaluated with `user=notification.user`, `request=None`, `notification=notification`. The evaluator exposes `user`, `notification` and `request` (a `PolicyRequest` whose `.context` also holds `notification`) (`SRC/authentik/core/expression/evaluator.py` lines 47-63).

So a mapping can rebuild today's payload exactly:

```python
# Webhook Body Mapping (sketch, not tested)
u = notification.user
ev = notification.event
return {
    "eventId": str(ev.event_uuid),
    "changedAt": ev.created.isoformat(),
    "tenantId": u.attributes.get("tenant_id", ""),
    "userId": str(u.uuid),            # or u.pk / u.username: whichever the backend keys logins by after ticket on claims
    "partyId": u.attributes.get("party_id", ""),
    "language": u.attributes.get("settings", {}).get("locale", "") or "en",
}
```

`event_uuid` and `created` are fields on `Event` (`models.py` lines 155-161). `attributes` holding `settings.locale` is shown in the docs' `user_write` example (https://docs.goauthentik.io/sys-mgmt/events/event-actions/). Whether we store the language there or in our own attribute is a design choice for the spec.

### Signing and shared secrets

- The transport has **no built-in signing, HMAC or secret field**. Its only fields are `name, mode, send_once, email_*, webhook_url, webhook_ca, webhook_mapping_body, webhook_mapping_headers` (`models.py` lines 356-405).
- The docs' only authentication example is a header mapping that returns `{"Authorization": "Bearer ..."}` (https://docs.goauthentik.io/sys-mgmt/events/transports/).
- Headers come from the **Webhook Header Mapping**, which returns a dict (`models.py` lines 459-466). It is evaluated **on every attempt**, because it runs inside `send_webhook` and that runs once per task execution.

Options, from weakest to strongest:

1. **Static shared secret header** (`Authorization: Bearer <secret>`, or a custom `X-…` header). Config only, and documented. There is no replay protection.
2. **HMAC in the header mapping.** The mapping is plain Python executed with `exec(ast_obj, self._globals, _locals)` (`SRC/authentik/lib/expression/evaluator.py` line 364).
   - `_globals` has no `__builtins__` key (lines 66-87). Per Python's `exec` contract, the real builtins are then inserted (https://docs.python.org/3/library/functions.html#exec), so `import hmac, hashlib, time` should work.
   - **UNCONFIRMED by docs.** The expression docs neither allow nor forbid imports (https://docs.goauthentik.io/customize/policies/expression/). This is inferred from code and not tested.
   - Caveat: the header mapping runs separately from the body mapping and does not see the serialized bytes. `requests` serializes `json=` with its own `json.dumps` formatting, so an HMAC over "the raw body" would mean reproducing that serialization. That is brittle.
   - The robust variant signs a **canonical string** that both sides build, for example `timestamp + "." + eventId + "." + userId`, from the same `notification` object. It sends `X-…-Timestamp` and `X-…-Signature` headers, and the backend recomputes the signature from the parsed body fields.
   - The secret would sit inside the mapping expression (admin-editable, and stored in the blueprint). UNCONFIRMED: whether a blueprint `!Env` tag can inject it from an environment variable instead of committing it. Ticket on blueprints/secrets should confirm.
3. **Signed JWT.** The globals include `ak_create_jwt_raw(provider, validity="seconds=60", **claims)`, which returns `provider.encode(claims)` with `exp` and `aud` set (`SRC/authentik/lib/expression/evaluator.py` lines 70, 244-252). The docs say it "does _not_ require an HTTP request and allows for setting custom claims via `**kwargs`" (https://docs.goauthentik.io/add-secure-apps/providers/property-mappings/expression/).
   - A header mapping can return `{"Authorization": "Bearer " + ak_create_jwt_raw("lp-backend", eventId=..., tenantId=..., partyId=..., ...)}`, so the payload rides **inside** the signed token.
   - The backend already validates this provider's JWTs against Authentik's JWKS, so this needs **no extra shared secret**, gives a 60-second expiry (re-minted on every retry because headers are re-evaluated), and binds the data to the signature.
   - UNCONFIRMED: that `provider.encode` uses the provider's configured signing key (RS256/ES256, the same key as its access tokens). I did not read `OAuth2Provider.encode`.

TLS: `webhook_ca` can pin a CA to verify the backend's certificate (`models.py` lines 374-383, 486-488). There is no client certificate (mTLS) option (`authentication_kp=None` at line 487).

**Answer:** the default body is thin (message, severity, email, username). A body mapping can send anything on the user (pk, uuid, username, attributes such as `tenant_id` and `party_id`) and on the event (uuid, created). There is no native signing. A shared-secret header is config only and documented. HMAC (inferred) or a provider-signed JWT via `ak_create_jwt_raw` are possible inside a header mapping without code changes to Authentik.

## 3. Delivery: synchronous, retried, or best-effort?

**Asynchronous, persisted, retried, then dropped.**

- **Off the request path.** Saving the event fires `post_save`, which only enqueues `event_trigger_dispatch` (`SRC/authentik/events/signals.py` lines 133-138).
  - That task fans out one `event_trigger_handler` per rule (`tasks.py` lines 26-29). The handler evaluates the policies and enqueues one `notification_transport` per destination (lines 38-97). That last task performs the POST (lines 100-123).
  - None of this blocks the user's password change, and nothing about the webhook's outcome is fed back to the flow.
- **The queue is Postgres.** The broker is `authentik.tasks.broker.Broker(PostgresBroker)` (`SRC/authentik/tasks/broker.py`; `SRC/authentik/root/settings.py` lines 422-424). Tasks are rows (`update_or_create`, `SRC/packages/django-dramatiq-postgres/django_dramatiq_postgres/broker.py` line 197), so queued and retrying deliveries survive a worker restart.
- **Failure detection.** `send_webhook` calls `response.raise_for_status()`. Any `RequestException`, which covers connection refused, timeout, **and any 4xx/5xx**, becomes `NotificationTransportError` (`models.py` lines 468-484). A 202 from us counts as success.
- **Timeout.** No explicit timeout is passed. `get_http_session()` applies `http_timeout` (`SRC/authentik/lib/utils/http.py` lines 76-83), and that defaults to **30 s** (`SRC/authentik/lib/default.yml` line 60). UNCONFIRMED: the docs page does not list `http_timeout`/`AUTHENTIK_HTTP_TIMEOUT`, so this is from source only.
- **Retries.** `notification_transport` declares no `throws` / `max_retries` (`tasks.py` line 100), so the global Dramatiq `Retries` middleware applies: `max_retries = worker.task_max_retries` (default **5**) and `max_backoff = 1 h` (`settings.py` lines 472-477; `default.yml` line 176).
  - The docs say: "Configure how many times a failing task will be retried before abandoning. Defaults to 5." (https://docs.goauthentik.io/install-config/configuration/, `AUTHENTIK_WORKER__TASK_MAX_RETRIES`).
  - The pinned Dramatiq is 2.2.0 (`SRC/uv.lock`). Its `Retries` uses `DEFAULT_MIN_BACKOFF = 15000` ms and `compute_backoff(retries, factor=min_backoff)` = `15 s × 2^n × uniform(1,2)` (https://github.com/Bogdanp/dramatiq/blob/v2.2.0/dramatiq/middleware/retries.py lines 30, 107-124, 142-148; https://github.com/Bogdanp/dramatiq/blob/v2.2.0/dramatiq/common.py lines 41-72).
  - So there is 1 attempt plus 5 retries, roughly 15-30 s, 30-60 s, 60-120 s, 2-4 min, then 4-8 min apart. The **last attempt lands about 8-16 minutes** after the first. After that `message.fail()` runs and the notice is **dropped**. It is never sent later. Per-task status is kept in the DB for `task_expiration` (default 30 days, same docs page).
- **Retries are also applied to permanent errors.** A 400 or 401 from us is retried the same five times. There is no special-casing.

What this means for the backend:

- **If the backend is down** for less than about 8 minutes, the text still goes out late. If it is down longer, the text is lost, with a failed task visible in Authentik's admin UI under system tasks. That is still **strictly better than today**: Keycloak's notifier makes one attempt and only logs a failure (`PasswordChangeNotifier.java:51-59`).
- **Duplicates are possible.** If we process the request but the response is lost or times out (30 s), Authentik retries, and the person gets a second text. The backend should dedupe on the event UUID (`eventId` in the body sketch). Today's controller has no dedupe because Keycloak never retries.
- **Delayed texts would carry the wrong time.** `PasswordChangedNotices.text()` stamps "now". With retries the text should use the event's `created` time from the body instead.
- **Freshness checks must tolerate retries.** A timestamp-freshness check like today's `KeycloakSignature.signedAndFresh` still works, because the header mapping (and so the timestamp or JWT) is re-evaluated on every attempt. The freshness of the *event* itself is a separate matter, covered by the previous point.

## Verdict

**Natively, with configuration only: yes, with caveats the spec must carry.**

- **Event.** `password_set` fires for self-service changes, recovery-flow resets, admin resets and API/blueprint password sets alike.
- **Delivery.** Notification Rule (`destination_event_user`) + Event Matcher/expression policy + Generic Webhook transport + Webhook Body Mapping can POST a payload equivalent to today's (`tenantId`, `userId`, `partyId`, `language`, plus `eventId` and `changedAt`) to `/internal/password-changed`. All of these are blueprint-able models. No custom Authentik code is needed, so `PasswordChangedListener` and `PasswordChangeNotifier` can be deleted.
- **Gap 1: no built-in signing.** Trust must come from a header mapping. The choices are a static bearer secret (documented, weakest), an HMAC over canonical fields (inferred to work, untested), or, recommended for evaluation, a short-lived JWT from `ak_create_jwt_raw` that the backend verifies with Authentik's JWKS, carrying the payload in its claims (UNCONFIRMED which key signs it). `KeycloakSignature` and the `X-Reset-*` headers go away or are re-implemented in the mapping.
- **Gap 2: the admin and API paths are not distinguished.** Today admin resets are deliberately not texted. In Authentik the policy must exclude `/api/v3/core/users/…` request paths, or the backend's own login-creation and admin resets will trigger "your password was changed" texts. Alternatively, the spec decides that admin resets should text too.
- **Delivery semantics.** Delivery is asynchronous, queued in Postgres, and retried 5 times with exponential backoff (about 8-16 minutes in total), then dropped. Any non-2xx is retried. The backend must dedupe on the event id, use the event time in the text, and answer 2xx to anything it intentionally ignores, so it is not retried.
- **Minor.** The event is written before the password is saved, so a failed save could still text. This is acceptable, or can be guarded backend-side.
