<#import "template.ftl" as layout>
<#--
  The staff sign-in code (2026-11-14, lifeplatform-staff-sign-in-code): after the password, a staff member enters the
  six-digit code texted to the phone on their staff record. Two forms, as on the reset-code page: the code, and a
  separate "send a new code" -- so pressing Enter in the code field never quietly asks for another text.

  When the attempt has ended (three wrong codes, or no phone on the login) the page has no code field, only the way
  back to sign in.
-->
<@layout.registrationLayout hideUsername=true; section>
    <#if section = "header">
        ${msg("lpSignInCodeTitle")}

    <#elseif section = "form">
        <#if !(lpSignInEnded!false)>
            <p class="instruction">${msg("lpSignInCodeIntro", lpSignInCodeDigits, lpSignInMinutes)}</p>

            <form id="lp-sign-in-code-form" class="lp-form" action="${url.loginAction}" method="post" data-guard-submit>
                <div class="lp-group">
                    <label for="code" class="lp-label">${msg("lpSignInCodeLabel")}</label>
                    <input id="code" name="code" class="lp-input lp-input--code" type="text" autofocus
                           inputmode="numeric" autocomplete="one-time-code" maxlength="9"
                           pattern="[0-9 \-]*" spellcheck="false"
                           <#if message?has_content && message.type = 'error'>aria-invalid="true" aria-describedby="kc-alert"</#if>/>
                </div>
                <div class="lp-buttons">
                    <button type="submit" class="lp-btn lp-btn--primary lp-btn--block" data-label-busy="${msg('lpResetChecking')}">
                        <span class="lp-btn__spinner" aria-hidden="true" hidden>
                            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>
                        </span>
                        <span class="lp-btn__label">${msg("lpSignInCodeSubmit")}</span>
                    </button>
                </div>
            </form>

            <form id="lp-sign-in-resend-form" class="lp-form" action="${url.loginAction}" method="post">
                <input type="hidden" name="resend" value="on"/>
                <div class="lp-buttons">
                    <button type="submit" class="lp-btn lp-btn--outline lp-btn--block">${msg("lpSignInCodeResend")}</button>
                </div>
            </form>

            <p class="lp-hint">${msg("lpSignInCodeHelp")}</p>
        </#if>

        <div class="lp-options">
            <a class="lp-link" href="${url.loginRestartFlowUrl}">${msg("backToLogin")}</a>
        </div>
    </#if>
</@layout.registrationLayout>
