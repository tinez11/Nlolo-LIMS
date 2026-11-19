# Research: can Authentik text staff a sign-in code on every sign-in?

Ticket: [01-staff-sign-in-code](../issues/01-staff-sign-in-code.md). Researched 2026-11-19.

**Release examined:** authentik **2026.8.5** (latest stable, published 2026-10-21, per
https://api.github.com/repos/goauthentik/authentik/releases/latest). Docs read at version **2026.8** (the version
selector on every docs page cited). Source read at tag `version/2026.8.5`; links below use
`https://github.com/goauthentik/authentik/blob/version/2026.8.5/<path>#L<line>`.

## Today's behaviour (repo)

- `backend/keycloak/extensions/.../StaffSignInCodeAuthenticator.java`: after the password, users holding the realm
  role STAFF get a 6-digit code; others pass straight through. The extension makes the code, keeps only its salted hash,
  5-minute lifetime, 3 wrong codes end the attempt, at most 5 texts/hour and 1/minute per account, wrong code counts
  toward Keycloak brute-force lockout.
- `StaffCodeTexter.java`: `POST` JSON `{tenantId, userId, code, phone}` to `LP_STAFF_CODE_SMS_URL`, HMAC-signed
  (`X-Reset-Timestamp`, `X-Reset-Signature`, shared secret `LP_RESET_SMS_SECRET`).
- `StaffSignInCodeController.java` (`/internal/staff-sign-in-code/sms`): verifies the signature, texts the code through
  the platform gateway (phone, language, wording held by the platform) and **answers** `SENT | OFF | NO_PHONE |
  NOT_SENT`. `OFF` is how dev/tests switch codes off: Keycloak then lets the user in on the password alone.

## 1. Generic SMS provider: what exactly is sent?

**Yes, the generic provider posts to any URL, so it can target a backend endpoint.**

- The stage has `provider` = `twilio` | `generic`
  ([models.py L28-32](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_sms/models.py#L28-L32)).
  For generic, the URL is the `account_sid` field ("External API URL" in the UI)
  ([models.py L128-139](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_sms/models.py#L128-L139);
  docs: https://docs.goauthentik.io/add-secure-apps/flows-stages/stages/authenticator_sms/).
- **Method:** HTTP `POST`, body sent as JSON (`requests ... .post(url, json=payload, ...)`)
  ([models.py L129-139](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_sms/models.py#L129-L139)).
- **Default body:** `{"From": stage.from_number, "To": device.phone_number, "Body": <token>, "Message": "Use this code
  to authenticate in authentik: <token>"}`
  ([models.py L79-81, L110-115](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_sms/models.py#L79-L115)).
  So both the phone (`To`) and the code (`Body`) are there.
- **Custom body:** an optional `mapping` (a Webhook Mapping, `NotificationWebhookMapping`) replaces the whole payload.
  The expression gets `user` (the device owner), `request`, `device`, `token` and `stage`
  ([models.py L63-69, L117-126](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_sms/models.py#L117-L126)).
  So the mapping can send our existing shape, for example
  `return {"tenantId": user.attributes.get("tenant_id"), "userId": str(user.uuid), "code": token, "phone": device.phone_number}`.
  (`user.uuid` as the id is my assumption about the field name; UNCONFIRMED. `user.pk` is an integer in authentik.)
- **Authentication:** only `Authorization: Bearer <auth>` or HTTP Basic `(auth, auth_password)`
  ([models.py L35-39, L128-141](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_sms/models.py#L128-L141);
  docs page above, "Auth type"). **No request signing, no timestamp, no custom headers.** The mapping changes only the
  body. Today's HMAC check (`KeycloakSignature`) cannot be kept as is: the endpoint must accept a static bearer token
  (or basic auth) instead, still kept off the public address by Caddy.
- **Response:** only the status code is read. `raise_for_status()`; a status ≥ 400 becomes a `ValidationError` holding
  the response text, shown to the user, and a `CONFIGURATION_ERROR` event is logged
  ([models.py L143-161](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_sms/models.py#L143-L161)).
  **The response body is ignored**: the backend cannot answer `OFF` to let the user in, as today's controller does.
  `NO_PHONE`/`NOT_SENT` map onto a 4xx/5xx refusal, which keeps the user on the stage with an error (they are never let
  in on a failure, same as today).

## 2. Code only for staff, not agents/customers, on the same login page?

**Yes, natively, with a policy or group bound to the stage binding.**

- A flow-stage binding is a policy-binding target; "policies can be specified, which determine if this Binding applies
  to the current user" ([flows/models.py L255-271](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/flows/models.py#L255-L271)).
  Docs: bindings can be limited by binding a policy, a user or a group to them
  (https://docs.goauthentik.io/add-secure-apps/flows-stages/stages/).
- By default (`re_evaluate_policies=True`, `evaluate_on_plan=False`) the policies run right before the stage is shown,
  against the **pending user** (the person who just entered their username/password), and a failing binding removes
  the stage from the plan
  ([flows/markers.py L35-75](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/flows/markers.py#L35-L75);
  docs: https://docs.goauthentik.io/add-secure-apps/flows-stages/flow/, "evaluated dynamically, right before the stage
  is presented").
- Policy options: bind the staff group directly to the binding, or an expression policy
  `return ak_is_group_member(request.context["pending_user"], name="staff")`
  (`ak_is_group_member` and `pending_user`: https://docs.goauthentik.io/customize/policies/types/expression/reference/).
  Agents and customers fail the policy and skip the stage; the identification/password pages stay the same for all.

## 3. Phone from a user attribute, with no enrolment screen?

**Partly. There is no way to give a user an SMS device up front; one sign-in-time enrolment is unavoidable, but it can
be made invisible except for the code itself.**

How SMS works in authentik: the **Authenticator Validation** stage sends codes only to an existing, confirmed
`SMSDevice` row ([authenticator_validate/stage.py L168-232](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_validate/stage.py#L168-L232)).
The device holds its own `phone_number` ([authenticator_sms/models.py L200-210](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_sms/models.py#L200-L210)).
It does not read a user attribute.

- **Creating the device when the login is created: not possible via the API or blueprints.** The admin device API
  (`SMSAdminDeviceViewSet`, a full `ModelViewSet`) uses `SMSDeviceSerializer`, where `phone_number` and `user` are
  **read-only** ([authenticator_sms/api.py L46-57, L78-83](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_sms/api.py#L46-L83)).
  Blueprints build objects through the same model serializer
  ([blueprints/v1/importer.py L231-246](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/blueprints/v1/importer.py#L231-L246)),
  so they hit the same read-only fields. (Writing the row directly in authentik's database or through
  `ak shell` would work but is unsupported; not recommended.)
- **Enrol silently at first sign-in: possible with config.** The SMS setup stage skips its phone-number screen when the
  flow context already has `prompt_data.phone`: it sends the code straight to that number and shows only the code
  field (`phone_number_required=False`)
  ([authenticator_sms/stage.py L76-94, L101-126](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_sms/stage.py#L76-L126);
  docs: "If a Prompt stage collects a field with the key `phone`, the stage reads it from `prompt_data`").
  An expression policy on the binding can write that key from the user's attribute, because policies may modify
  `context["flow_plan"].context`
  (https://docs.goauthentik.io/customize/policies/expression/managing_flow_context_keys/; the planner passes the plan in,
  [markers.py L56-62](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/flows/markers.py#L56-L62)).
  Writing `prompt_data` specifically is not shown in the docs, only other keys; the source path above supports it, but
  it is UNCONFIRMED by a test.
- Wiring: Authenticator Validation stage, `device_classes: [sms]`, `not_configured_action: configure`,
  `configuration_stages: [the SMS setup stage]`
  ([authenticator_validate/models.py L45-68](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_validate/models.py#L45-L68),
  [stage.py L280-289](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_validate/stage.py#L280-L289)).
  On a staff member's first sign-in the setup stage runs, texts a code to the attribute phone, and the person types it:
  to them it looks like the normal sign-in code. Later sign-ins use the validate stage. The single device is
  auto-selected, which triggers the send
  ([web .../AuthenticatorValidateStage.ts L139-167, L201-205](https://github.com/goauthentik/authentik/blob/version/2026.8.5/web/src/flow/stages/authenticator_validate/AuthenticatorValidateStage.ts#L139-L205);
  send: [authenticator_validate/challenge.py L122-134](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_validate/challenge.py#L122-L134)).
- `last_auth_threshold` defaults to `seconds=0`, so a code is asked **every** sign-in
  ([authenticator_validate/models.py L70-77](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_validate/models.py#L70-L77)).
- If the staff user has no `phone` attribute, the same policy can return False with `ak_message`, and a validate stage
  with `not_configured_action: deny` stops the flow, matching today's `NO_PHONE` refusal (design sketch, not tested).

Simpler alternative that avoids depending on the device's number: the backend already resolves the phone from the
**staff record** and uses the login's phone only as a fallback. If the webhook keeps doing that and ignores `To`,
the device's stored number becomes only a key. It must still be non-empty and unique per stage
(`unique_together = (stage, phone_number)` and the duplicate check in `validate_and_send`:
[models.py L244-247](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_sms/models.py#L244-L247),
[stage.py L65-74](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_sms/stage.py#L65-L74)).

Caveats:
- The device's number is frozen at enrolment. A later phone change on the login attribute does not reach it unless the
  device is deleted (owners can delete their own SMS devices from user settings, `SMSDeviceViewSet` has `Destroy`,
  [api.py L60-75](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator_sms/api.py#L60-L75)).
  Admins can delete it through the admin device API. Routing by staff record in the backend (above) removes this issue.
- Staff could see an "SMS" authenticator in their user settings. Whether they can add a second one depends on the
  stage's `configure_flow`; leave it unset. UNCONFIRMED what the user-settings page shows with no configure flow.

## 4. Switch it off for dev and tests by configuration?

**Yes, by configuration, but not by the backend answering OFF.** Options:

- Blueprint entries take `conditions`; an entry whose conditions are false is skipped
  (https://docs.goauthentik.io/customize/blueprints/v1/structure/). `!Env VAR, default` reads an environment variable
  and `!If`/`!Condition` build conditions (https://docs.goauthentik.io/customize/blueprints/v1/tags/). So the stage
  binding for the code can be created only when e.g. `LP_STAFF_SIGN_IN_CODE=true`. Caveat: `conditions` only skip the
  entry; switching an existing install from on to off needs a `state: absent` entry (same docs page) under the opposite
  condition.
- Or keep the binding and put the on/off in the binding policy (e.g. read a flag on the Brand's attributes, or bind the
  policy only in prod blueprints). Whether expression policies can read OS environment variables (`import os`) is
  UNCONFIRMED; the reference page lists no import restriction but shows no such example.
- `ProductionSettingsGuard` must then check the new switch (the backend can no longer say OFF itself). Note the backend
  should still refuse to text in dev when the switch is off, as a second line.

## Behaviour that does not carry over exactly

| Today (Keycloak extension) | Authentik 2026.8.5 | Source |
|---|---|---|
| HMAC-signed request with timestamp | Bearer or Basic static secret only | models.py L128-141 |
| Backend answers OFF / NO_PHONE / NOT_SENT | Only HTTP status is read; ≥400 shows the response text as an error | models.py L143-161 |
| Code made by the extension, hash in session | Code generated by authentik and stored on the device row (`token`) | [authenticator/models.py](https://github.com/goauthentik/authentik/blob/version/2026.8.5/authentik/stages/authenticator/models.py) `SideChannelDevice.generate_token` |
| 5-minute lifetime | 5 minutes (`generate_token(valid_secs=300)` default, called with no args for SMS) | authenticator/models.py `generate_token`; challenge.py L131-134 |
| 3 wrong codes end the attempt | Exponential back-off on wrong codes (`ThrottlingMixin`, factor `sms_otp_throttling_factor`), no hard 3-try stop | authenticator/models.py `ThrottlingMixin`; authenticator_validate/models.py L95-125 |
| Max 5 texts/hour, 1/minute per account | No send-rate limit found in the SMS send path. The backend webhook can enforce it by returning 429 (the user sees the error text). UNCONFIRMED whether a Reputation policy or frontend resend timer covers it | challenge.py L122-134 |
| Wrong code counts toward brute-force lockout | Authentik uses Reputation policies; whether a failed MFA code lowers reputation is UNCONFIRMED | — |
| Platform wording and language | Backend composes the text from `code` and user; the mapping can pass `request`-derived locale if needed | models.py L117-126 |

## Verdict

**Feasible with configuration only, no custom authentik code**, with these accepted differences that need tickets
rather than silent drops:

1. **Native and config only:** generic SMS provider → backend webhook (JSON POST, payload fully mappable to today's
   `{tenantId, userId, code, phone}`); staff-only via a group/policy on the stage binding; a code at every sign-in
   (`last_auth_threshold=0`); dev off through blueprint `conditions` with `!Env`.
2. **Workaround (config):** no device can be pre-created by API or blueprint (`phone_number` is read-only). Use
   validate stage `not_configured_action: configure` + SMS setup stage, with an expression policy that puts the login's
   `phone` attribute into `prompt_data.phone`. The first sign-in texts a code with no phone screen. Prefer routing by the
   staff record in the backend so the device's frozen number does not matter. Needs a spike to confirm the `prompt_data`
   injection.
3. **Gaps to ticket:** (a) the webhook must switch from HMAC signature to a bearer secret; (b) the backend can't answer
   OFF, so the on/off moves into authentik config and `ProductionSettingsGuard` must check it; (c) the 3-try hard stop
   becomes exponential back-off; (d) the 5/hour, 1/minute send cap must be enforced by the backend webhook (return 4xx).
