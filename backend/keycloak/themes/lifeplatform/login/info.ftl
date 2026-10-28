<#import "template.ftl" as layout>
<#--
  Keycloak's information page (2026-10-28). Two moments of every invitation pass through it -- a staff member's, an
  agent's, a client's: opening the emailed link, and after choosing the password. Base said "Perform the following
  action(s): Update Password » Click here to proceed", then "Your account has been updated." and a small text link,
  printing the same sentence as both the title and the paragraph. Here the link opens on what is about to happen and
  one button to do it; the end says the password is set and how to go on. Any other message keeps base's shape.
-->
<#assign confirming = (actionUri?has_content) && !(pageRedirectUri?has_content)>
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "header">
        <#if confirming>
            ${msg("lpActionsTitle")}
        <#elseif messageHeader??>
            ${kcSanitize(msg("${messageHeader}"))?no_esc}
        <#else>
            ${kcSanitize(message.summary)?no_esc}
        </#if>

    <#elseif section = "form">
        <div id="kc-info-message">
            <#if confirming && requiredActions??>
                <p class="instruction">${msg("lpActionsIntro")} <#list requiredActions as action><b>${kcSanitize(msg("requiredAction.${action}"))?no_esc}</b><#sep>, </#list>.</p>
            <#elseif messageHeader??>
                <p class="instruction">${kcSanitize(message.summary)?no_esc}</p>
            </#if>

            <#if skipLink??>
            <#elseif confirming>
                <div class="lp-buttons">
                    <a class="lp-btn lp-btn--primary lp-btn--block" href="${actionUri}">${msg("proceedWithAction")}</a>
                </div>
            <#elseif pageRedirectUri?has_content>
                <div class="lp-buttons">
                    <a class="lp-btn lp-btn--primary lp-btn--block" href="${pageRedirectUri}">${msg("backToApplication")}</a>
                </div>
            <#elseif (client.baseUrl)?has_content>
                <div class="lp-buttons">
                    <a class="lp-btn lp-btn--primary lp-btn--block" href="${client.baseUrl}">${msg("backToApplication")}</a>
                </div>
            </#if>
        </div>
    </#if>
</@layout.registrationLayout>
