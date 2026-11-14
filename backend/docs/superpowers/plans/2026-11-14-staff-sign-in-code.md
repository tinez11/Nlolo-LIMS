# Staff Sign-in Code Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After the password, every staff sign-in asks for a 6-digit code texted to the phone on the staff record.

**Architecture:** A new Keycloak authenticator (`lifeplatform-staff-sign-in-code`) runs after username/password in a custom browser flow. For a `staff` user it makes a code, keeps only its hash, and asks the platform — signed, like the reset code — to text it. The platform owns the switch (`app.staff-sign-in-code.enabled`), the staff phone and language, and the editable SMS template; it answers `SENT`, `OFF`, `NO_PHONE` or `NOT_SENT`. With the switch off the authenticator lets the person straight in, so dev and the e2e suite are unchanged. Administrators gain "Change phone and language" on a staff login (recorded in the access history).

**Tech Stack:** Keycloak 26 SPI (Java 21), Spring Boot 3, PostgreSQL 16, React 19, Vitest.

**Spec:** `backend/docs/superpowers/specs/2026-11-14-staff-sign-in-code-design.md`

## Global Constraints

- Staff only (Keycloak realm role `staff`); agents, customers and regulators sign in as today.
- Every staff sign-in; no "remember this computer" (Keycloak's own SSO cookie in the same browser session still applies).
- Code: 6 digits, valid 5 minutes, single use; 3 wrong codes end the attempt (back to the sign-in page); a wrong code also counts towards the realm's lockout.
- At most one new code a minute; at most 5 texts an hour per account. (Spec said 3, the reset figure; sign-ins are far more frequent than resets — a staff member whose 30-minute session lapses twice and mistypes once would be locked out at 3. Flag to the user.)
- No phone on the staff record: refused with "No phone number is set for your login. Ask an administrator."
- Text: SMS template `STAFF_SIGN_IN_CODE`, en and sw, editable in Communications > Templates. Starting wording:
  - en: `Your Nlolo sign-in code is {{code}}. It expires in 5 minutes. Never share it.`
  - sw: `Namba yako ya kuingia Nlolo ni {{code}}. Itaisha baada ya dakika 5. Usimpe mtu yeyote.` (draft; user to check)
  If the wording in force lost `{{code}}` (the template editor allows dropping a placeholder), the built-in English wording is sent instead and a warning logged.
- The code is never logged, never stored, never put in the message log (`sendSystemSms`).
- Switch: `app.staff-sign-in-code.enabled` (env `STAFF_SIGN_IN_CODE`), default false. `ProductionSettingsGuard` refuses the prod profile with it off.
- An administrator cannot change their own phone (it is where their code goes), as they cannot change their own access.
- Dev: SMS only to the allowlist `255755418369`; never print `SMS_GATEWAY_AUTHORIZATION`.
- Host Maven only (`JAVA_HOME=~/.jdks/jdk-21.0.12.1+1`); stop the dev backend before backend test runs; one heavy run at a time; logs to the scratchpad; Write/Edit for files; never Prettier; never `git add backend/docs` as a directory.
- Commit trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## File Map

Backend:
- `db-migrations/iam/V8__staff_text_language.sql` — `staff_user.text_language`.
- `db-migrations/communication/V24__staff_sign_in_code_template.sql` — the two templates.
- `iam/api/StaffContacts.java` — add `languageOf`.
- `iam/application/StaffContactsImpl.java` — implement it.
- `iam/application/AccessAdministration.java` — `UserView.textLanguage`, `updateContact(...)`, `CONTACT_CHANGED`.
- `iam/infrastructure/AccessAdminController.java` — `PUT /access/users/{userId}/contact`.
- `communication/api/NotificationApi.java` + `communication/application/NotificationApiImpl.java` — `renderSystemTemplate(...)`.
- `omnichannel/application/StaffSignInCodes.java` — decide and send.
- `omnichannel/infrastructure/StaffSignInCodeController.java` — `POST /internal/staff-sign-in-code/sms`.
- `iam/infrastructure/SecurityConfig.java` — permit that path (signature is its guard).
- `ProductionSettingsGuard.java` — refuse prod with the switch off.
- `src/main/resources/application.yml` — the switch.
- `api/openapi/openapi-iam.yaml` — contact endpoint, `textLanguage`, `CONTACT_CHANGED`.
- Tests: `omnichannel/infrastructure/StaffSignInCodeControllerTest.java`, `omnichannel/application/StaffSignInCodesTest.java`, `iam/AccessAdministrationIntegrationTest.java` (+V8 in its list and `AccessIntegrationTest`'s), `ProductionSettingsGuardTest.java`.

Keycloak (`backend/keycloak/`):
- `extensions/.../SignInCodes.java` — constants + per-account cap (pure, tested).
- `extensions/.../StaffCodeTexter.java` — signed HTTP call, reads the answer.
- `extensions/.../StaffSignInCodeAuthenticator.java` + `StaffSignInCodeAuthenticatorFactory.java`; register in `META-INF/services/org.keycloak.authentication.AuthenticatorFactory`.
- `extensions/src/test/.../SignInCodesTest.java`.
- `themes/lifeplatform/login/lp-sign-in-code.ftl`; messages en/sw.
- `lifeplatform-realm.json` — flow `lifeplatform browser` and `"browserFlow"`.
- `backend/infra/docker-compose.yml`, `deploy/server/compose.yml` — `LP_STAFF_CODE_SMS_URL`.
- `backend/scripts/apply-staff-sign-in-flow.sh` — puts the flow on the running dev realm (import skips an existing realm).

Frontend: `src/api/access.ts`, `src/features/admin/UserDetailPage.tsx` (+ test), `src/features/admin/changeText.ts` (+ test), users list "No phone" marker.

---

### Task 1: Staff text language and changing a staff phone

**Files:** iam V8; `StaffContacts`, `StaffContactsImpl`, `AccessAdministration`, `AccessAdminController`; `openapi-iam.yaml`; `AccessAdministrationIntegrationTest`, `AccessIntegrationTest` (add V8 to their migration lists).

**Interfaces — produces:**
- `StaffContacts.languageOf(UUID userId): String` — `"en"` or `"sw"`, `"en"` when unknown.
- `AccessAdministration.updateContact(Actor actor, UUID userId, String phone, String textLanguage): UserView`
- `UserView` gains `String textLanguage` (last component).
- `PUT /access/users/{userId}/contact` body `{phone: string|null, textLanguage: "en"|"sw"}` → `UserView`; `users.manage`.

- [ ] **Step 1: Migration**

```sql
-- db-migrations/iam/V8__staff_text_language.sql
-- 2026-11-14, staff sign-in code: the language a staff member's texts are in (their sign-in code). English unless an
-- administrator says otherwise. Re-runnable.
ALTER TABLE iam.staff_user ADD COLUMN IF NOT EXISTS text_language VARCHAR(2) NOT NULL DEFAULT 'en';
ALTER TABLE iam.staff_user DROP CONSTRAINT IF EXISTS ck_staff_user_text_language;
ALTER TABLE iam.staff_user ADD CONSTRAINT ck_staff_user_text_language CHECK (text_language IN ('en', 'sw'));
```

Add `"db-migrations/iam/V8__staff_text_language.sql"` after V1 in `AccessAdministrationIntegrationTest` and `AccessIntegrationTest` (`grep -rl "db-migrations/iam/" backend/src/test` — those two only).

- [ ] **Step 2: Failing tests** in `AccessAdministrationIntegrationTest` (follow its existing helpers for an actor and a created user):

```java
    @Test
    void anAdministratorChangesAStaffPhoneAndLanguageAndTheHistorySaysSo() {
        // create a user the way the class's other tests do, as `admin`
        UserView changed = admin.updateContact(adminActor, user.userId(), "+255 755 418 369", "sw");
        assertThat(changed.phone()).isEqualTo("+255 755 418 369");
        assertThat(changed.textLanguage()).isEqualTo("sw");
        assertThat(admin.user(user.userId()).history()).anySatisfy(c -> {
            assertThat(c.changeType()).isEqualTo("CONTACT_CHANGED");
            assertThat(c.detail()).containsEntry("textLanguage", "sw");
        });
        assertThat(contacts.languageOf(user.userId())).isEqualTo("sw");
        assertThat(contacts.phoneOf(user.userId())).contains("+255 755 418 369");
    }

    @Test
    void nobodyChangesTheirOwnPhoneAndALanguageIsEnOrSw() {
        assertThatThrownBy(() -> admin.updateContact(adminActor, adminActor.userId(), "0755000000", "en"))
            .isInstanceOf(AccessChangeRefusedException.class);
        assertThatThrownBy(() -> admin.updateContact(adminActor, user.userId(), "0755000000", "fr"))
            .isInstanceOf(AccessChangeRefusedException.class);
    }
```

The history detail records the phone's last three digits only (`"phoneEnds": "369"` or `"phoneRemoved": true`), never the whole number, and the language.

- [ ] **Step 3: Implement.** `StaffContacts`:

```java
    /** The language of texts to {@code userId}: "en" or "sw" (2026-11-14, staff sign-in code). "en" when unknown. */
    String languageOf(UUID userId);
```

`StaffContactsImpl.languageOf`: `SELECT text_language FROM iam.staff_user WHERE tenant_id = :tenant AND user_id = :user`, first value or `"en"`.

`AccessAdministration`: add `text_language` to the `UserView` query/mapping (`(String) row.get("text_language")`), and:

```java
    /**
     * The phone and text language on a staff login (2026-11-14, staff sign-in code): where their sign-in code goes.
     * Never one's own -- an administrator who could repoint their own code could pass the check with any phone.
     */
    @Transactional
    public UserView updateContact(Actor actor, UUID userId, String phone, String textLanguage) {
        UUID tenant = TenantContext.get();
        refuseSelf(actor, userId);
        UserView before = userView(tenant, userId);
        String language = textLanguage == null ? "en" : textLanguage.trim();
        if (!language.equals("en") && !language.equals("sw")) {
            throw new AccessChangeRefusedException(INVALID, "Texts are in English (en) or Kiswahili (sw).");
        }
        String newPhone = blankToNull(phone);
        jdbc.update("UPDATE iam.staff_user SET phone = :phone, text_language = :language WHERE tenant_id = :tenant"
                + " AND user_id = :user",
            new MapSqlParameterSource("tenant", tenant).addValue("user", userId).addValue("phone", newPhone)
                .addValue("language", language));
        Map<String, Object> detail = new LinkedHashMap<>();
        if (newPhone == null) detail.put("phoneRemoved", true);
        else detail.put("phoneEnds", newPhone.replaceAll("\\D", "").replaceAll(".*(\\d{3})$", "$1"));
        detail.put("textLanguage", language);
        record(tenant, userId, null, "CONTACT_CHANGED", detail, "APPLIED", actor);
        return userView(tenant, userId);
    }
```

Use the class's existing refusal-kind constant for a bad request (read `AccessChangeRefusedException` and the constants beside `NOT_FOUND`; if no 422-kind exists, use the one `createUser` uses for a bad username).

Controller:

```java
    public record ContactRequest(String phone, String textLanguage) {}

    @PutMapping("/users/{userId}/contact")
    @PreAuthorize("@access.can('users.manage')")
    public AccessAdministration.UserView updateContact(@PathVariable UUID userId, @RequestBody ContactRequest request) {
        return admin.updateContact(actor(), userId, request.phone(), request.textLanguage());
    }
```

OpenAPI: `textLanguage: { type: string, enum: [en, sw] }` added to `UserView` (and its `required`), `CONTACT_CHANGED` to the `changeType` enum, and the new path (responses 200 `UserView`, 401, 403, 404, 409/422 as `/access/users/{userId}/active` declares).

- [ ] **Step 4: Run** `AccessAdministrationIntegrationTest,AccessIntegrationTest,IamContractTest` (find the iam contract test with `grep -rl "openapi-iam" backend/src/test`). Expected: pass.

- [ ] **Step 5: Commit** `feat(iam): a staff login's phone and text language can be changed, recorded in its history`.

---

### Task 2: The sign-in code text, on the platform

**Files:** communication V24; `NotificationApi`/`NotificationApiImpl` (`renderSystemTemplate`); `StaffSignInCodes`; `StaffSignInCodeController`; `SecurityConfig`; `application.yml`; `ProductionSettingsGuard`; tests.

**Interfaces — produces:**
- `NotificationApi.renderSystemTemplate(String templateKey, String language, Map<String,String> values): Optional<String>` — the current tenant's SMS wording (tenant's own, else the platform's; the language, else sw as `notify` does), rendered; empty when none or a placeholder is unfilled.
- `StaffSignInCodes.send(UUID userId, String code): Result` where `enum Result { SENT, OFF, NO_PHONE, NOT_SENT }`.
- `POST /internal/staff-sign-in-code/sms` body `{"tenantId","userId","code"}`, headers as the reset request; `200 {"result":"SENT|OFF|NO_PHONE|NOT_SENT"}`, `401` unsigned/stale, `400` malformed.

- [ ] **Step 1: Template migration** (same shape as V23):

```sql
-- db-migrations/communication/V24__staff_sign_in_code_template.sql
-- 2026-11-14, staff sign-in code: the text carrying a staff member's sign-in code. Editable like any template; the
-- sender falls back to its own English wording if an edit drops {{code}}. Swahili is a DRAFT for the user to check.
-- Platform defaults (the zero tenant). Re-runnable.
INSERT INTO communication.notification_template (tenant_id, template_key, channel, language, body_template)
SELECT * FROM (VALUES
  ('00000000-0000-0000-0000-000000000000'::uuid, 'STAFF_SIGN_IN_CODE', 'SMS', 'en',
   'Your Nlolo sign-in code is {{code}}. It expires in 5 minutes. Never share it.'),
  ('00000000-0000-0000-0000-000000000000'::uuid, 'STAFF_SIGN_IN_CODE', 'SMS', 'sw',
   'Namba yako ya kuingia Nlolo ni {{code}}. Itaisha baada ya dakika 5. Usimpe mtu yeyote.')
) AS t(tenant_id, template_key, channel, language, body_template)
WHERE NOT EXISTS (
  SELECT 1 FROM communication.notification_template n
   WHERE n.tenant_id = t.tenant_id AND n.template_key = t.template_key AND n.channel = t.channel
     AND n.language = t.language);
```

Add V24 after V23 in every test list naming `communication/V23__maturity_payout_redirected_template.sql` (check composed lists first, as before).

- [ ] **Step 2: Failing tests.** `StaffSignInCodesTest` (plain unit test, mocks for `StaffContacts`, `NotificationApi`):

```java
    @Test void offSendsNothing() { /* enabled=false -> OFF, no lookup, no send */ }
    @Test void noPhoneIsSaidAndNothingSent() { /* phoneOf empty -> NO_PHONE */ }
    @Test void theTemplateInTheirLanguageCarriesTheCode() {
        // languageOf -> "sw"; renderSystemTemplate("STAFF_SIGN_IN_CODE","sw",{code=123456}) -> "Namba ... 123456 ..."
        // sendSystemSms(phone, "STAFF_SIGN_IN_CODE", that text) returns null -> SENT
    }
    @Test void aWordingThatLostTheCodeFallsBackToTheBuiltInText() {
        // render returns "Your code." (no 123456) -> sends "Your Nlolo sign-in code is 123456. It expires in 5 minutes. Never share it."
    }
    @Test void aGatewayRefusalIsNotSent() { /* sendSystemSms returns "not on allowlist" -> NOT_SENT */ }
```

Write each with real Mockito `when(...)`/`verify(...)` calls against the signatures above. `StaffSignInCodeControllerTest` copies `PasswordChangedControllerTest`'s `sign(...)` helper: signed → 200 with the service's result as `{"result":"SENT"}`; unsigned, wrong secret, stale → 401; malformed body → 400.

- [ ] **Step 3: Implement.**

`NotificationApiImpl`:

```java
    @Override
    @Transactional(readOnly = true)
    public Optional<String> renderSystemTemplate(String templateKey, String language, Map<String, String> values) {
        return findEffectiveTemplate(TenantContext.get(), templateKey, NotificationChannel.SMS.name(), language)
            .flatMap(t -> {
                try {
                    return Optional.of(TemplateRenderer.render(t.getBodyTemplate(), values));
                } catch (RuntimeException unfilled) {
                    return Optional.empty();
                }
            });
    }
```

`StaffSignInCodes` (omnichannel/application):

```java
/**
 * A staff member's sign-in code, texted (2026-11-14, spec 2026-11-14-staff-sign-in-code-design.md). Keycloak makes the
 * code and asks; the platform holds the switch, the phone, the language and the wording. The code is never logged.
 */
@Service
public class StaffSignInCodes {

    public enum Result { SENT, OFF, NO_PHONE, NOT_SENT }

    static final String TEMPLATE = "STAFF_SIGN_IN_CODE";
    private static final Logger log = LoggerFactory.getLogger(StaffSignInCodes.class);

    private final boolean enabled;
    private final StaffContacts staff;
    private final NotificationApi notifications;

    public StaffSignInCodes(@Value("${app.staff-sign-in-code.enabled:false}") boolean enabled, StaffContacts staff,
                            NotificationApi notifications) {
        this.enabled = enabled;
        this.staff = staff;
        this.notifications = notifications;
    }

    public Result send(UUID userId, String code) {
        if (!enabled) return Result.OFF;
        String phone = staff.phoneOf(userId).orElse(null);
        if (phone == null) return Result.NO_PHONE;
        String body = notifications.renderSystemTemplate(TEMPLATE, staff.languageOf(userId), Map.of("code", code))
            .filter(text -> text.contains(code))
            .orElseGet(() -> {
                log.warn("The {} wording in force does not carry the code; sent the built-in text", TEMPLATE);
                return builtIn(code);
            });
        String failure = notifications.sendSystemSms(phone, TEMPLATE, body);
        if (failure != null) {
            log.warn("Sign-in code for staff user {} not texted: {}", userId, failure);
            return Result.NOT_SENT;
        }
        return Result.SENT;
    }

    static String builtIn(String code) {
        return "Your Nlolo sign-in code is " + code + ". It expires in 5 minutes. Never share it.";
    }
}
```

`StaffSignInCodeController` (omnichannel/infrastructure) — copy `PasswordChangedController`'s constructor pair and signature check; parse `tenantId`, `userId`, `code` (code must match `\d{6}`, else 400); `TenantContext.set(tenantId)` around `codes.send(...)`; return `ResponseEntity.ok(Map.of("result", result.name()))`. `PATH = "/internal/staff-sign-in-code/sms"`.

`SecurityConfig`: beside the two existing lines, `.requestMatchers(HttpMethod.POST, "/internal/staff-sign-in-code/sms").permitAll()`.

`application.yml` (near `password-reset`):

```yaml
app:
  # 2026-11-14: a texted code after the password on every staff sign-in. Off in dev and tests; prod refuses to start
  # with it off (ProductionSettingsGuard).
  staff-sign-in-code:
    enabled: ${STAFF_SIGN_IN_CODE:false}
```

(merge into the existing `app:` block — do not create a second `app:` key.)

`ProductionSettingsGuard.problems`: 

```java
        if (!"true".equalsIgnoreCase(settings.apply("app.staff-sign-in-code.enabled"))) {
            problems.add("app.staff-sign-in-code.enabled is off (STAFF_SIGN_IN_CODE: staff must get a sign-in code)");
        }
```

Add a case to `ProductionSettingsGuardTest` (it builds a settings map; add the key as "true" to the all-good map and assert the problem when absent). Also add `STAFF_SIGN_IN_CODE=true` to `deploy/release.env.example` or the server `.env` example where `SUPPORT_PHONE` is listed (`grep -rn SUPPORT_PHONE deploy`).

- [ ] **Step 4: Run** `StaffSignInCodesTest,StaffSignInCodeControllerTest,ProductionSettingsGuardTest,ModularityTests` plus one Spring test that loads communication V24 (`grep -rl "communication/V24" backend/src/test | head -1`). Expected: pass.

- [ ] **Step 5: Commit** `feat(staff sign-in): the platform texts a staff member's sign-in code, behind a switch`.

---

### Task 3: The Keycloak step

**Files:** extension `SignInCodes`, `StaffCodeTexter`, `StaffSignInCodeAuthenticator`, `...Factory`, services file, `SignInCodesTest`; theme `lp-sign-in-code.ftl` + messages; realm JSON; both compose files; `scripts/apply-staff-sign-in-flow.sh`.

**Interfaces — consumes:** Task 2's endpoint and answers. Reuses `ResetCodes.newCode/newSalt/hash/matches/recentSends/store/sign` and `ResetDeliveryAuthenticator.STAFF_ROLE`.

- [ ] **Step 1: Failing test** `SignInCodesTest`:

```java
class SignInCodesTest {
    @Test void fiveTextsAnHourAndOneAMinute() {
        long now = 10_000;
        assertThat(SignInCodes.maySend(List.of(), now)).isTrue();
        assertThat(SignInCodes.maySend(List.of(now - 30), now)).isFalse();          // a minute apart
        assertThat(SignInCodes.maySend(List.of(now - 3000, now - 2400, now - 1800, now - 1200), now)).isTrue();
        assertThat(SignInCodes.maySend(List.of(now - 3000, now - 2400, now - 1800, now - 1200, now - 600), now)).isFalse();
    }
    @Test void theAnswerIsReadFromThePlatformsReply() {
        assertThat(SignInCodes.result("{\"result\":\"SENT\"}")).isEqualTo("SENT");
        assertThat(SignInCodes.result("{\"result\":\"OFF\"}")).isEqualTo("OFF");
        assertThat(SignInCodes.result("garbage")).isEqualTo("NOT_SENT");
        assertThat(SignInCodes.result(null)).isEqualTo("NOT_SENT");
    }
}
```

Run: `./mvnw -f keycloak/extensions/pom.xml -B -o test` → fails (no class).

- [ ] **Step 2: Implement `SignInCodes`:**

```java
/** The sign-in code's limits (2026-11-14). The code arithmetic itself is ResetCodes'. */
final class SignInCodes {
    static final int LIFETIME_SECONDS = 5 * 60;
    static final int MAX_ATTEMPTS = 3;
    static final int MAX_SENDS_PER_HOUR = 5;
    static final int RESEND_GAP_SECONDS = 60;

    private SignInCodes() {}

    static boolean maySend(List<Long> recentSends, long nowSeconds) {
        if (recentSends.size() >= MAX_SENDS_PER_HOUR) return false;
        long newest = recentSends.stream().mapToLong(Long::longValue).max().orElse(Long.MIN_VALUE / 2);
        return nowSeconds - newest >= RESEND_GAP_SECONDS;
    }

    /** SENT, OFF, NO_PHONE or NOT_SENT from {"result":"..."}; anything unreadable is NOT_SENT -- never let in. */
    static String result(String body) {
        if (body == null) return "NOT_SENT";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"result\"\\s*:\\s*\"(SENT|OFF|NO_PHONE|NOT_SENT)\"")
            .matcher(body);
        return m.find() ? m.group(1) : "NOT_SENT";
    }
}
```

`StaffCodeTexter` — `ResetCodeTexter`'s shape, but returns the answer: body `{"tenantId","userId","code"}`, signed with `ResetCodes.sign`, `HttpResponse.BodyHandlers.ofString()`, `status == 200 ? SignInCodes.result(body) : "NOT_SENT"`; unreachable or unconfigured → `"NOT_SENT"` (logged without the code).

`StaffSignInCodeAuthenticator` (notes prefixed `lp.signin.`):
- `authenticate`: user null or not holding `STAFF_ROLE` → `context.success()`. A live code (hash note present, not expired) → redraw the page. Else `deliver`.
- `deliver`: per-account cap via `session.singleUseObjects()` key `lp-signin-sends:<realm>:<user>` exactly as `withinHourlyCap` (using `SignInCodes.maySend`); over it → page with error `lpSignInCodeWait`. New code/salt/hash/expiry/attempts notes; `answer = texter.send(tenant_id attribute, user.getId(), code)`:
  - `OFF` → remove the notes, `context.success()` (the platform has the code switched off).
  - `NO_PHONE` → `context.failure(AuthenticationFlowError.ACCESS_DENIED, page(error "lpSignInNoPhone"))`.
  - `NOT_SENT` → page with error `lpSignInCodeNotSent` (resend offered).
  - `SENT` → page. Nothing about the code is logged or put in an event.
- `action`: `resend` → gap check (`lpSignInCodeWaitMinute`) else `deliver`. Expired → error `lpSignInCodeExpired` (resend offered). Wrong → attempts+1; at 3 → remove notes, `context.failure(AuthenticationFlowError.INVALID_CREDENTIALS, page(error "lpSignInTooManyAttempts"))`; below 3 → `context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, page(error "lpSignInWrongCode"))` (failureChallenge is what feeds the brute-force counter). Right → remove hash/salt, `context.success()`.
- `requiresUser()` true; `configuredFor` true.

Factory: id `lifeplatform-staff-sign-in-code`, texter from env `LP_STAFF_CODE_SMS_URL` and `LP_RESET_SMS_SECRET` (the same shared secret), `REQUIRED` only. Add the factory's class name to `META-INF/services/org.keycloak.authentication.AuthenticatorFactory`.

- [ ] **Step 3: Theme.** `lp-sign-in-code.ftl` — `lp-reset-code.ftl` with: header `lpSignInCodeTitle`; intro `msg("lpSignInCodeIntro", lpSignInCodeDigits, lpSignInMinutes)`; same code input and resend form; `backToLogin` link. Messages:

en:
```
lpSignInCodeTitle=Enter your sign-in code
lpSignInCodeIntro=We have texted a {0}-digit code to the phone on your staff record. It works for {1} minutes.
lpSignInCodeLabel=Code from the text message
lpSignInCodeSubmit=Sign in
lpSignInCodeResend=Send a new code
lpSignInWrongCode=That code is not right. Check the text message and try again.
lpSignInTooManyAttempts=Too many wrong codes. Sign in again to get a new one.
lpSignInCodeExpired=That code has expired. Send a new code.
lpSignInCodeNotSent=We could not send your code. Send a new code, or ask your administrator.
lpSignInCodeWait=Too many codes this hour. Wait, then sign in again.
lpSignInCodeWaitMinute=Wait a minute before asking for another code.
lpSignInNoPhone=No phone number is set for your login. Ask an administrator.
```
sw:
```
lpSignInCodeTitle=Weka namba yako ya kuingia
lpSignInCodeIntro=Tumetuma namba ya tarakimu {0} kwa ujumbe mfupi kwenye simu iliyo kwenye kumbukumbu zako za mfanyakazi. Inatumika kwa dakika {1}.
lpSignInCodeLabel=Namba kutoka kwenye ujumbe mfupi
lpSignInCodeSubmit=Ingia
lpSignInCodeResend=Tuma namba mpya
lpSignInWrongCode=Namba hiyo si sahihi. Angalia ujumbe mfupi kisha ujaribu tena.
lpSignInTooManyAttempts=Umekosea mara nyingi. Ingia tena upate namba mpya.
lpSignInCodeExpired=Namba hiyo imepitwa na muda. Tuma namba mpya.
lpSignInCodeNotSent=Hatukuweza kutuma namba yako. Tuma namba mpya, au mwulize msimamizi wako.
lpSignInCodeWait=Namba nyingi mno saa hii. Subiri, kisha uingie tena.
lpSignInCodeWaitMinute=Subiri dakika moja kabla ya kuomba namba nyingine.
lpSignInNoPhone=Hakuna namba ya simu kwenye akaunti yako. Mwulize msimamizi.
```
(Swahili is a draft, as the reset messages were; flag to the user.)

- [ ] **Step 4: Realm flow** in `lifeplatform-realm.json`: `"browserFlow": "lifeplatform browser"` beside `resetCredentialsFlow`, and two flows:

```json
{ "alias": "lifeplatform browser", "description": "Sign in; staff then enter a texted code (2026-11-14)",
  "providerId": "basic-flow", "topLevel": true, "builtIn": false,
  "authenticationExecutions": [
    { "authenticator": "auth-cookie", "authenticatorFlow": false, "requirement": "ALTERNATIVE", "priority": 10, "userSetupAllowed": false },
    { "authenticatorFlow": true, "requirement": "ALTERNATIVE", "priority": 30, "flowAlias": "lifeplatform browser forms", "userSetupAllowed": false }
  ] },
{ "alias": "lifeplatform browser forms", "description": "Username and password, then the staff sign-in code",
  "providerId": "basic-flow", "topLevel": false, "builtIn": false,
  "authenticationExecutions": [
    { "authenticator": "auth-username-password-form", "authenticatorFlow": false, "requirement": "REQUIRED", "priority": 10, "userSetupAllowed": false },
    { "authenticator": "lifeplatform-staff-sign-in-code", "authenticatorFlow": false, "requirement": "REQUIRED", "priority": 20, "userSetupAllowed": false }
  ] }
```

(include `"autheticatorFlow"` beside `"authenticatorFlow"` as the existing flows do.) Run `node deploy/keycloak/build-realms.test.mjs` (or its documented test command) — the prod realm is generated from this file.

Compose: `LP_STAFF_CODE_SMS_URL: ${LP_STAFF_CODE_SMS_URL:-http://host.docker.internal:8080/internal/staff-sign-in-code/sms}` in `backend/infra/docker-compose.yml`; `LP_STAFF_CODE_SMS_URL: http://backend:8080/internal/staff-sign-in-code/sms` in `deploy/server/compose.yml`.

`scripts/apply-staff-sign-in-flow.sh` (dev only — the realm is imported once): with `kcadm.sh` in the Keycloak container, create the two flows and executions above and set the realm's `browserFlow`; idempotent (skip when `lifeplatform browser` exists). Read how the 2026-11-06 password-changed listener was added to dev (memory: kcadm) and copy that login step.

- [ ] **Step 5: Build and run.** `./mvnw -f keycloak/extensions/pom.xml -B -o package` → jar; `docker compose up -d keycloak` from `backend/infra` (recreate to pick up the env); run the apply script. Check: with the dev backend's switch OFF, every e2e staff login still signs straight in (run `auth-finance.setup.ts` alone).

- [ ] **Step 6: Commit** `feat(staff sign-in): Keycloak asks staff for a texted code after the password`.

---

### Task 4: Users and roles — phone and language

**Files:** `frontend/src/api/access.ts`, `UserDetailPage.tsx` (+ test), `changeText.ts` (+ test), the users list (find with `grep -rn "listStaffUsers" frontend/src/features/admin`).

- [ ] **Step 1:** `npm run generate:api`; `access.ts`: `export function updateStaffContact(userId: string, phone: string | null, textLanguage: 'en' | 'sw'): Promise<StaffUser> { return put<StaffUser>(\`/access/users/${encodeURIComponent(userId)}/contact\`, { phone, textLanguage }); }`.
- [ ] **Step 2: Tests first.** `changeText.test.ts`: `CONTACT_CHANGED` with `{phoneEnds:'369', textLanguage:'sw'}` → `"Changed Rehema's phone (ends 369), texts in Kiswahili"`; with `{phoneRemoved:true, textLanguage:'en'}` → `"Removed Rehema's phone, texts in English"`. `UserDetailPage.test.tsx`: a viewer with `users.manage` sees "Change phone" on another user, not on themselves; saving calls `updateStaffContact(id, '+255…', 'sw')`; a user with no phone shows "No phone — they cannot get a sign-in code".
- [ ] **Step 3: Implement** — `changeText.ts` case; on `UserDetailPage` beside the Phone field: language field ("Texts in: English / Kiswahili"), a "Change phone" inline form (phone input, language select, Save/Cancel, `InlineError`), hidden for oneself; the "No phone" line in warning colour (`text-status-warning-fg`); the users list shows "No phone" for active users without one. Invoke `impeccable` for the panel's look; copy the page's existing field and button conventions.
- [ ] **Step 4: Run** `npx vitest run src/features/admin`, `npm run typecheck`, eslint on touched files.
- [ ] **Step 5: Commit** `feat(console): set a staff member's phone and text language; flag logins with no phone`.

---

### Task 5: Live check on dev and merge

- [ ] Apply iam V8 and communication V24 to dev by psql. Restart the dev backend with `STAFF_SIGN_IN_CODE=true`, `SMS_GATEWAY_LIVE=true` and **the SMS mock** as base URL (`SMS_GATEWAY_BASE_URL=http://localhost:8083`, Mode TEST), so no real phone is texted; give staff.finance a phone via the new page.
- [ ] Sign in as staff.finance in a scripted browser: the code page appears; read the code from the mock's request journal (`GET http://localhost:8083/__admin/requests`), enter a wrong code (error), then the right one (console opens). Third wrong code returns to sign-in. A staff login with no phone sees the no-phone message. An agent signs in with no code.
- [ ] Then restart the dev backend as usual (switch OFF, live SMS with the allowlist).
- [ ] Merge gate: affected backend classes (Tasks 1–2), extension tests, `npx vitest run` in full (milestone), the e2e sign-in setups + `staff-users` specs (`ls frontend/e2e | grep -i user`), whole-branch review, `merge --no-ff`, push.

## Self-review notes

- Spec coverage: who/when/code rules (T3), no-phone (T2/T3/T4), editable EN+SW template (T2), language on staff login (T1), switch + prod guard (T2), dev off / e2e unchanged (T2/T3/T5), Users and roles marker (T4), audit of phone change (T1), events without the code (T3).
- Deviations to tell the user: 5 texts an hour, not 3; no automated e2e with the switch on (the suite runs against the shared dev backend whose switch is off) — replaced by the scripted live check in T5 against the SMS mock; an administrator cannot change their own phone.
