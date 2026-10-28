<#import "template.ftl" as layout>
<#--
  Choosing a new password (2026-10-28): the last step of a reset and of a first sign-in. Base opened it with a yellow
  "You need to change your password." -- a warning, as though something had gone wrong, on a page the person came to
  on purpose -- and kept the rules secret until one was broken. Here the rules come first, in the order the server
  checks them, and only an error gets an alert. "Sign out on every other device" stays ticked: after a reset it is
  what throws out whoever else had the account.

  The rules sentence mirrors the realms' password policy (backend/keycloak/*-realm.json and
  deploy/keycloak/build-realms.mjs): length 10, not the username or email, not one of the last three.
-->
<#macro revealButton target>
    <button type="button" class="lp-reveal" data-password-toggle aria-controls="${target}" aria-pressed="false"
            aria-label="${msg('showPassword')}" hidden>
        <svg class="lp-reveal__show" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>
        <svg class="lp-reveal__hide" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" hidden><path d="M9.88 9.88a3 3 0 1 0 4.24 4.24"/><path d="M10.73 5.08A10.43 10.43 0 0 1 12 5c7 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68"/><path d="M6.61 6.61A13.526 13.526 0 0 0 2 12s3 7 10 7a9.74 9.74 0 0 0 5.39-1.61"/><path d="m2 2 20 20"/></svg>
    </button>
</#macro>
<#assign passwordError = messagesPerField.existsError('password')>
<#assign confirmError = messagesPerField.existsError('password-confirm')>
<@layout.registrationLayout displayMessage=(message?has_content && message.type = 'error' && !passwordError && !confirmError); section>
    <#if section = "header">
        ${msg("updatePasswordTitle")}

    <#elseif section = "form">
        <p class="instruction">${msg("lpNewPasswordRules")}</p>

        <form id="kc-passwd-update-form" class="lp-form" action="${url.loginAction}" method="post" data-guard-submit>
            <div class="lp-group">
                <label for="password-new" class="lp-label">${msg("passwordNew")}</label>
                <div class="lp-inputgroup">
                    <input type="password" id="password-new" name="password-new" class="lp-input lp-input--reveal"
                           autofocus autocomplete="new-password"
                           <#if passwordError>aria-invalid="true" aria-describedby="input-error-password"</#if>/>
                    <@revealButton target="password-new"/>
                </div>
                <#if passwordError>
                    <span id="input-error-password" class="lp-err" aria-live="polite">${kcSanitize(messagesPerField.get('password'))?no_esc}</span>
                </#if>
            </div>

            <div class="lp-group">
                <label for="password-confirm" class="lp-label">${msg("passwordConfirm")}</label>
                <div class="lp-inputgroup">
                    <input type="password" id="password-confirm" name="password-confirm" class="lp-input lp-input--reveal"
                           autocomplete="new-password"
                           <#if confirmError>aria-invalid="true" aria-describedby="input-error-password-confirm"</#if>/>
                    <@revealButton target="password-confirm"/>
                </div>
                <#if confirmError>
                    <span id="input-error-password-confirm" class="lp-err" aria-live="polite">${kcSanitize(messagesPerField.get('password-confirm'))?no_esc}</span>
                </#if>
            </div>

            <div class="lp-check">
                <input type="checkbox" id="logout-sessions" name="logout-sessions" value="on" class="lp-check__input" checked/>
                <label for="logout-sessions" class="lp-check__label">${msg("logoutOtherSessions")}</label>
            </div>

            <div class="lp-buttons">
                <#if isAppInitiatedAction??>
                    <button type="submit" class="lp-btn lp-btn--primary">${msg("lpSaveNewPassword")}</button>
                    <button type="submit" name="cancel-aia" value="true" class="lp-btn lp-btn--outline">${msg("doCancel")}</button>
                <#else>
                    <button type="submit" class="lp-btn lp-btn--primary lp-btn--block" data-label-busy="${msg('lpSavingPassword')}">
                        <span class="lp-btn__spinner" aria-hidden="true" hidden>
                            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>
                        </span>
                        <span class="lp-btn__label">${msg("lpSaveNewPassword")}</span>
                    </button>
                </#if>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
