<#import "template.ftl" as layout>
<#--
  The code page of the agents' and customers' reset (2026-10-28, lifeplatform-reset-delivery). Everyone who asks for a
  reset lands here, whatever they typed: the sentence says what was sent IF the name matches an account, so the page
  never tells anyone whether it did. The emailed link works too, which is why the page says "whichever arrives first".

  Two forms: the code, and a separate "send a new code" -- separate so pressing Enter in the code field can never
  quietly ask for another text instead.
-->
<@layout.registrationLayout hideUsername=true; section>
    <#if section = "header">
        ${msg("lpResetCodeTitle")}

    <#elseif section = "form">
        <p class="instruction">${msg("lpResetCodeIntro", lpResetCodeDigits, lpResetMinutes)}</p>

        <form id="lp-reset-code-form" class="lp-form" action="${url.loginAction}" method="post" data-guard-submit>
            <div class="lp-group">
                <label for="code" class="lp-label">${msg("lpResetCodeLabel")}</label>
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
                    <span class="lp-btn__label">${msg("lpResetCodeSubmit")}</span>
                </button>
            </div>
        </form>

        <form id="lp-reset-resend-form" class="lp-form" action="${url.loginAction}" method="post">
            <input type="hidden" name="resend" value="on"/>
            <div class="lp-buttons">
                <button type="submit" class="lp-btn lp-btn--outline lp-btn--block">${msg("lpResetResend")}</button>
            </div>
        </form>

        <p class="lp-hint">${msg("lpResetHelp")}</p>

        <div class="lp-options">
            <a class="lp-link" href="${url.loginRestartFlowUrl}">${msg("backToLogin")}</a>
        </div>
    </#if>
</@layout.registrationLayout>
