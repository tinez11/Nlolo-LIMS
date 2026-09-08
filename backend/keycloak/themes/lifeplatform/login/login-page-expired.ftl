<#import "template.ftl" as layout>
<#--
  Overridden because base's version reads
  "To restart the login process click here . To continue the login process click here ."
  -- two identical link texts, a stray space before each full stop, and no
  statement of what either one costs.

  It is also the page a staff member hits most often after the login itself: a
  tab left open over lunch, or a back button after a redirect. Two labelled
  routes, each naming its own outcome.
-->
<@layout.registrationLayout; section>
    <#if section = "header">
        ${msg("pageExpiredTitle")}

    <#elseif section = "form">
        <p class="instruction">
            The sign-in attempt timed out. Continuing keeps the page you were heading to;
            starting again returns you to a blank form.
        </p>

        <div class="lp-stack">
            <a id="loginContinueLink" class="lp-btn lp-btn--primary lp-btn--block" href="${url.loginAction}">${msg("pageExpiredMsg2")}</a>
            <a id="loginRestartLink" class="lp-btn lp-btn--outline lp-btn--block" href="${url.loginRestartFlowUrl}">${msg("pageExpiredMsg1")}</a>
        </div>
    </#if>
</@layout.registrationLayout>
