<#import "template.ftl" as layout>
<#--
  "Forgot your password?" (2026-10-28). Base put the explanation under the button, "Back to sign in" between the field
  and the button, and labelled the button "Submit". Here it reads in the order a person acts: what will happen, the
  one field, the button that says what it sends, and the way back.

  The agents' and customers' realms send a link AND a code by text (lifeplatform-reset-delivery); staff and regulators
  are sent the link alone. The sentence says which, so nobody waits for a text that is not coming.
-->
<#assign bothWays = realm.name == "agents" || realm.name == "customers">
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username'); section>
    <#if section = "header">
        ${msg("emailForgotTitle")}

    <#elseif section = "form">
        <p class="instruction"><#if bothWays>${msg("lpResetIntroSms")}<#else>${msg("lpResetIntroEmail")}</#if></p>

        <form id="kc-reset-password-form" class="lp-form" action="${url.loginAction}" method="post" data-guard-submit>
            <div class="lp-group">
                <label for="username" class="lp-label"><#if !realm.loginWithEmailAllowed>${msg("lpUsernameLabel")}<#elseif !realm.registrationEmailAsUsername>${msg("lpUsernameOrEmailLabel")}<#else>${msg("lpEmailLabel")}</#if></label>
                <input id="username" name="username" class="lp-input" type="text" autofocus
                       value="${(auth.attemptedUsername!'')}"
                       autocomplete="username" autocapitalize="off" autocorrect="off" spellcheck="false"
                       <#if messagesPerField.existsError('username')>aria-invalid="true" aria-describedby="input-error-username"</#if>/>
                <#if messagesPerField.existsError('username')>
                    <span id="input-error-username" class="lp-err" aria-live="polite">${kcSanitize(messagesPerField.get('username'))?no_esc}</span>
                </#if>
            </div>

            <div class="lp-buttons">
                <button type="submit" class="lp-btn lp-btn--primary lp-btn--block" data-label-busy="${msg('lpResetSending')}">
                    <span class="lp-btn__spinner" aria-hidden="true" hidden>
                        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>
                    </span>
                    <span class="lp-btn__label"><#if bothWays>${msg("lpResetSendBoth")}<#else>${msg("lpResetSend")}</#if></span>
                </button>
            </div>

            <div class="lp-options">
                <a class="lp-link" href="${url.loginUrl}">${msg("backToLogin")}</a>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
