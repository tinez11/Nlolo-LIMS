# Research 02: can Authentik send a password reset by SMS only?

Ticket: [02-sms-password-reset](../issues/02-sms-password-reset.md). Researched 2026-11-19.

**Version checked:** authentik **2026.8.5**, the latest release (published 2026-10-21, per `https://api.github.com/repos/goauthentik/authentik/releases/latest`). The docs at docs.goauthentik.io show "2026.8". Source links below point at tag `version/2026.8.5`.

`SRC` = `https://github.com/goauthentik/authentik/blob/version/2026.8.5/`

## 0. A correction to the ticket: today the person gets a code, not a link

The ticket says the backend "texts the person a link that works for 15 minutes". The code does something different:

- `ResetDeliveryAuthenticator.deliver` makes a **six-digit code** and stores only its hash in the auth session, with a 15-minute expiry. It texts that code through `ResetCodeTexter`. The person types the code into the reset page. A link exists only when the account has an email address *and* the realm has an SMTP host, and with email unused that never happens.
  - Code: `backend/keycloak/extensions/.../ResetDeliveryAuthenticator.java`, `deliver()` and `action()`.
  - Constants in `ResetCodes.java`: `DIGITS = 6`, `LIFETIME_SECONDS = 15 * 60`, `MAX_ATTEMPTS = 5`, `MAX_SENDS_PER_HOUR = 3`, `RESEND_GAP_SECONDS = 60`.
- `PasswordResetTexts.text()` says "Your password reset code is … It works for 15 minutes", or in Swahili "Namba yako ya kubadilisha nenosiri ni … dakika 15".
- **Staff are refused** by realm role `staff`. They are sent nothing, and the page looks the same as for anyone, so it doesn't reveal who is staff.
- **Unknown names** get the same page too.
- **Language** comes from Keycloak's own locale for the user, `resolveLocale(user)`, and is sent as `language` in the signed JSON. It does not come from the backend's client record.
- **The webhook** is `POST /internal/password-reset/sms`. Its body is `{tenantId, partyId, code, language}`, signed with HMAC (`KeycloakSignature`). It always answers 202.

The settled constraint in map.md, "a 15-minute link", therefore changes how the reset is delivered. The answers below cover both: a texted **link**, which Authentik can do natively with token expiry, and a texted **code**, which Authentik can do only with a fixed 5-minute lifetime.

## 1. Can a recovery flow deliver by SMS with no email stage?

**Yes, but not through a stage built for it.** Authentik has no recovery stage that texts a link. There are three building blocks.

### 1a. The Email stage can only send email

The Email stage creates a `FlowToken` holding the paused flow plan, then mails the link via `send_mails`. The recipient is the user's address or the plan-context `email` override.

- Source: `SRC/authentik/stages/email/stage.py#L78-L131`. It sends via `TemplateEmailMessage`/`send_mails` and has no other channel.
- Docs list only SMTP delivery, either global or stage-specific settings: https://docs.goauthentik.io/add-secure-apps/flows-stages/stages/email/
- Not usable here, because email is never a channel.

### 1b. The SMS authenticator stage texts a code to an enrolled device, never a link

- `AuthenticatorSMSStage` has providers `twilio` and `generic`.
  - The **generic** provider POSTs JSON `{"From","To","Body": token,"Message"}` to a configurable URL, with Basic or Bearer auth.
  - An optional `NotificationWebhookMapping` can replace the payload. The mapping is evaluated with `user=device.user`, `request`, `device`, `token` and `stage`.
  - Source: `SRC/authentik/stages/authenticator_sms/models.py#L28-L161`. The generic send is at L108-L161; the mapping evaluation is at L117-L126.
  - Docs: https://docs.goauthentik.io/add-secure-apps/flows-stages/stages/authenticator_sms/
  - Any response of 400 or above is treated as a failed send and blocks the user (models.py L159-L160; also stated in the docs).
- The code is sent to `SMSDevice.phone_number`, so the user needs an **enrolled SMS device**.
  - Model: `models.py#L200-L247`.
  - The code is sent when the Authenticator Validation stage selects that device: `SRC/authentik/stages/authenticator_validate/challenge.py#L131-L134`.
  - The backend could create devices through the admin API, since `SMSAdminDeviceViewSet` is a full `ModelViewSet`: `SRC/authentik/stages/authenticator_sms/api.py#L78-L84`.
- Using this in a recovery flow means Identification → Authenticator Validation (device class `sms`) → Prompt (new password) → User Write. It has three problems:
  1. **The code lifetime is fixed at 5 minutes, not 15.** `select_challenge_sms` calls `device.generate_token()` with no arguments, and the default is `valid_secs=300`. See `challenge.py#L131-L134` and `SRC/authentik/stages/authenticator/models.py#L237-L252`. Email devices get a configurable expiry (`challenge.py#L137-L141`); SMS devices do not.
  2. **It reveals whether an account exists.** With `pretend_user_exists`, a typed number that isn't a user gives an unsaved `pending_user` with no devices (`authenticator_validate/stage.py#L175-L180`). The stage then either skips ahead (`not_configured_action=skip`) or shows "No (allowed) MFA authenticator configured." (`deny`), at `stage.py#L280-L289`. A real user gets a code page. Today's "same page for every name" rule is lost.
  3. Staff will hold SMS devices for the staff sign-in code (ticket 01). Staff must therefore be shut out by a policy, which in turn shows a different page (see §3).

### 1c. Recovery link from the API, plus a backend webhook (recommended)

- `POST /api/v3/core/users/{id}/recovery/` takes `{"token_duration": "<timedelta string>"}` and returns `{"link": …}`. It needs the `authentik_core.reset_user_password` permission.
  - It plans the **active brand's recovery flow** with `pending_user` set, and stores the plan in a `FlowToken`. The token's identifier is `"{user.uid}-password-reset"`, created with `update_or_create`, so a new request replaces the old link. It has `revoke_on_execution=True`, so the link works once, and `expires = now + token_duration`.
  - Source: `SRC/authentik/core/api/users.py#L521-L525` (serializer), `#L729-L775` (`_create_recovery_link`), `#L974-L987` (`recovery` action).
  - Docs: the link needs a recovery flow set as the brand's **Default recovery flow**. When the link is sent by hand, "the email stage is not required and can be removed": https://docs.goauthentik.io/users-sources/user/user_basic_operations/
- Expression policies can make outbound HTTP calls. `requests` is a global (a requests Session).
  - Source: `SRC/authentik/lib/expression/evaluator.py#L66-L87`.
  - Docs: https://docs.goauthentik.io/customize/policies/types/expression/reference/
  - The expression is run with plain `exec(..., self._globals, _locals)` and builtins are not stripped (`evaluator.py#L364`), so `import hmac, hashlib, time` should work. This is inferred from the source; the docs don't say whether imports are allowed (UNCONFIRMED in docs).
- **Design that meets the requirement with configuration only on the Authentik side:**
  - **Flow A, "Forgot password?"**, is the Identification stage's `recovery_flow`. That field is separate from the brand's recovery flow (`SRC/authentik/stages/identification/models.py#L110`, used at `identification/stage.py#L394-L399`).
    1. Identification stage with `user_fields=["username"]` and `pretend_user_exists=true`.
    2. A Deny stage whose `deny_message` reads "If this number has an account, we have texted a link". `deny/stage.py` returns `stage_invalid(deny_message)`.
       - Bound to that stage, with `re_evaluate_policies=true` (`SRC/authentik/flows/models.py#L264-L271`), is an expression policy. It POSTs the signed `{username}` (or the `party_id`/`tenant_id` attributes) to a backend webhook and always returns `True`.
  - **The backend:**
    1. Checks its own rules: client vs staff, the hourly cap, a known party with a phone.
    2. Calls `POST /core/users/{pk}/recovery/` with `token_duration: "minutes=15"`.
    3. Texts the link through the existing gateway in the client's language.
  - **Flow B, the brand's default recovery flow**, is Prompt (new password ×2) → User Write → User Login. The person reaches it only through the texted link.
  - There is no email stage anywhere.
- **Caveats found in the source:**
  - The link's host comes from `request.build_absolute_uri(...)` on the API call (`users.py#L769-L773`). If the backend calls Authentik on its internal address, the link would carry that internal host. The backend must call Authentik's public hostname, send the public `Host`, or rebuild the URL from the returned `flow_token`.
  - The brand is resolved from that same request (`request.brand`, `users.py#L734-L737`), so the default or matching brand must have `flow_recovery` set.
  - A policy with side effects is safe from result caching here. Results are cached only for authenticated users (`SRC/authentik/policies/types.py#L48-L54`), and the person resetting is anonymous.
  - How the Deny stage's page looks (it is an error-style "denied" screen) is UNCONFIRMED visually. Ticket 09 (login look) should check that wording and look are acceptable.

## 2. Can the link's lifetime be 15 minutes?

**Yes, for a link.**

- `token_duration` takes any timedelta string, e.g. `"minutes=15"`, and sets `FlowToken.expires = now() + 15 min` (`users.py#L757-L760`).
- Expiry is enforced on use. `_check_flow_token` looks the token up with `FlowToken.objects.filter(key=key)` (`SRC/authentik/flows/views/executor.py#L138-L157`). The default manager of `ExpiringModel` excludes `expires < now()` (`SRC/authentik/lib/models.py#L131-L147`), and `FlowToken` inherits it through `Token` (`core/models.py#L1174`, `flows/models.py#L336`).
- The token is deleted on first use (`revoke_on_execution`, executor L149-L150).
- For comparison, the Email stage's `token_expiry` is also configurable, but adds one minute of grace (`email/stage.py#L82-L84`).

**No, for an SMS code.** It is hard-coded to 300 seconds (§1b). Changing that would mean patching Authentik.

## 3. Can staff be refused while agents and customers are allowed?

**Yes, in two places.**

- **The backend refuses** (simplest, and keeps today's rule in one place): the webhook simply texts nothing for staff. The page is identical either way, as today.
- **Authentik refuses:** bind a policy to flow B, either a negated binding to the `staff` group or `return not ak_is_group_member(request.context["pending_user"], name="staff")`.
  - `ak_is_group_member` is documented at https://docs.goauthentik.io/customize/policies/types/expression/reference/
  - When planning a flow, the policy engine runs against `pending_user` (`SRC/authentik/flows/planner.py#L277-L292`). The recovery API then fails with "Recovery flow not applicable to user" (`users.py#L748-L751`), so a staff member can never get a link even if the backend asked for one. This is defence in depth, and the staff member never sees the failure because the backend makes that call.
- Avoid putting a refusing policy in front of the person (on flow A's stages). It would show a different page for staff and break today's no-disclosure rule.

## 4. What does the person type to start recovery?

- They type the **username**, which is the phone number. The Identification stage's `user_fields` can be any of `email`, `username` and `upn`. `get_user` matches with an exact or `iexact` match on those fields (`identification/stage.py#L262-L290`; stage field `identification/models.py#L28`). Leave email off.
- There is no normalisation hook: "0755…" and "255755…" would not both match. UNCONFIRMED whether today's Keycloak flow normalises either; check `build-realms.mjs` or the theme during the spec. A policy cannot rewrite the identifier before lookup in this stage (not found in the source read).
- When the number is unknown, `pretend_user_exists` sets an unsaved `User(username=uid)` as `pending_user` and carries on (`identification/stage.py#L199-L212`). The flow A policy therefore still runs and can post the typed number. The backend finds no login and texts nothing, and the page is identical.
- Docs: https://docs.goauthentik.io/add-secure-apps/flows-stages/stages/identification/

## 5. Can the backend learn the person's language?

**Yes.**

- In the link design (1c), the policy can send whatever it likes: `pending_user.username`, `pending_user.pk`, or `pending_user.attributes["party_id"]` / `["tenant_id"]`. The backend then looks up the client's language itself, which is better than today's Keycloak-locale guess.
- In the code design (1b), the SMS mapping receives `user=device.user` (`authenticator_sms/models.py#L117-L126`), so the payload can also carry username and attributes.
- Authentik's own `User.locale()` reads `attributes.settings.locale` or the request language (`core/models.py#L611-L622`). It is not needed if the backend decides.

**Webhook security:** the existing HMAC "timestamp.body" scheme can be reproduced in the expression with `hmac`/`hashlib`, given the import caveat above. The secret can be injected into the blueprint with `!Env` (https://docs.goauthentik.io/customize/blueprints/v1/tags/), which keeps it out of the repo YAML. The stored expression would still hold it in Authentik's database.

## Verdict

- **No native "recovery by SMS" stage exists.**
  - The Email stage sends email only.
  - The SMS authenticator stage texts a code, only to enrolled devices, with a fixed 5-minute lifetime. It also reveals whether an account exists, so it fails both the 15-minute and the no-disclosure requirements.
- **Achievable with configuration plus backend code, and no Authentik extension**, as an SMS link valid for 15 minutes in EN/SW, with staff refused:
  - Flow A (Identification with `pretend_user_exists` → Deny stage carrying the "we've texted you" message) has one expression policy that calls a signed backend webhook.
  - The backend checks role and cap, calls Authentik's documented `POST /core/users/{id}/recovery/` with `token_duration=minutes=15`, and texts the single-use link through the existing gateway in the client's language.
  - Flow B (the brand's recovery flow: new password → write → login) is guarded by a "not staff" policy as a second check.
  - All of this is blueprint YAML plus a backend endpoint that replaces `PasswordResetSmsController` and calls the Authentik admin API.
- **Gaps that become tickets, not silent drops:**
  1. **Code becomes link.** Today's texted six-digit code would become a texted link. Map.md already says "link", but this is a user-visible change and the SMS wording changes.
  2. **The link host.** The backend must call Authentik's public hostname, or rebuild the link from the returned `flow_token`.
  3. **The "texted" page.** The Deny stage's error-style page carries the message; the look is unverified, so it belongs to ticket 09.
  4. **Number formats.** Matching is exact, so variant formats of the number are not normalised.
  5. **Rate limits.** Today's 5-attempt and 60-second resend gaps lose meaning with a single-use 15-minute link. The 3-per-hour cap stays in `PasswordResetTexts`.
  6. **An Authentik API token.** The backend needs a token holding `reset_user_password`; this joins the admin-API tokens the `Keycloak*Logins` adapters already need.
