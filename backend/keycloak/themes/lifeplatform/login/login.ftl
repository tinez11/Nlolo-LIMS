<#import "template.ftl" as layout>

<#--
  The front door, following the pinned reference's form structure: centred mark,
  a large welcome, a small realm line, label-above-field inputs, a full-width
  pill action, then the remember / help row and the third-party block.

  ## Three slots in the reference that this platform cannot honestly fill

  The reference offers "Sign up", "Trouble logging in?" and Google / Facebook /
  Apple sign-in. On this platform all four realms set `registrationAllowed`
  false and `resetPasswordAllowed` false, and no realm has an identity provider
  configured. Rendering any of them would put a dead control on the one page a
  person cannot get past.

  So each is written as the conditional it should have been, keyed on what the
  realm actually has switched on -- the reset link and the provider list appear
  by themselves the day someone enables them -- and the region the reference
  spends on third-party buttons carries `pageNote` instead: the true answer to
  the question those two missing links would have answered.

  ## The subtitle tracks configuration rather than asserting it

  `accessNote` is assembled from the realm's own flags, so the sentence stops
  being printed the moment it stops being true. Assigned as a plain string
  rather than captured from a block: Keycloak runs FreeMarker with HTML
  auto-escaping on, so a captured assignment evaluates to markup output and
  every string builtin on it -- ?trim included -- fails at render time with a
  NonStringException.
-->
<#assign accessNote = "">
<#if !realm.registrationAllowed && !realm.resetPasswordAllowed>
    <#assign accessNote = "Accounts and password resets are handled by your administrator.">
<#elseif !realm.registrationAllowed>
    <#assign accessNote = "Accounts are issued by your administrator.">
</#if>

<#assign realmLabel = (realm.displayName!'')?trim>
<#if realmLabel == ''>
    <#assign realmLabel = (realm.name)!''>
</#if>

<#--
  True when Keycloak attached an error to either credential field. On bad
  credentials it attaches the SAME message to both, so rendering it per-field
  would print one problem twice and announce it twice. It is a statement about
  the pair, not about a field, so it renders once as the form-level alert
  `template.ftl` already owns, and both controls point at it through
  aria-describedby while taking aria-invalid. What a field looks like and what
  it announces cannot disagree, because the invalid border keys off that same
  attribute.
-->
<#assign credentialsRejected = messagesPerField.existsError('username','password')>

<@layout.registrationLayout displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled?? pageRealm=realmLabel pageNote=accessNote; section>

    <#if section = "header">
        ${msg("loginAccountTitle")}

    <#elseif section = "form">
        <#if realm.password>
            <#--
              No inline onsubmit handler. Base ships `onsubmit="login.disabled = true"`,
              which drops the submit button's own parameter from the POST and
              leaves the form dead if the server answers with an error. The
              double-submit guard and the busy state live in js/login.js
              instead, and the form posts normally with JavaScript off.
            -->
            <form id="kc-form-login" class="lp-form" action="${url.loginAction}" method="post" data-guard-submit>

                <#--
                  Belt and braces: Keycloak sets `message` alongside the
                  per-field error, so template.ftl has already rendered the
                  alert the fields below reference. If a flow ever attaches a
                  field error without a summary, this keeps the reason on screen
                  instead of leaving two silently-red fields.
                -->
                <#if credentialsRejected && !(message?has_content)>
                    <div id="kc-alert" class="lp-alert lp-alert--error" role="alert">
                        <span class="lp-alert__icon" aria-hidden="true">
                            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/></svg>
                        </span>
                        <span class="lp-alert__text">${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}</span>
                    </div>
                </#if>

                <#if !usernameHidden??>
                    <div class="lp-group">
                        <#-- The reference's possessive phrasing -- "Your email
                             address" -- kept, but the noun follows what the
                             realm actually accepts. Whole strings rather than
                             `msg("username")?lower_case`: a string builtin on a
                             message is the same auto-escaping trap as a
                             captured assign, and it would take the front door
                             down with a NonStringException rather than
                             degrading. Translators get real sentences too. -->
                        <label for="username" class="lp-label"><#if !realm.loginWithEmailAllowed>${msg("lpUsernameLabel")}<#elseif !realm.registrationEmailAsUsername>${msg("lpUsernameOrEmailLabel")}<#else>${msg("lpEmailLabel")}</#if></label>
                        <input id="username" name="username" class="lp-input" type="text"
                               value="${(login.username!'')}"
                               autofocus
                               autocomplete="username"
                               autocapitalize="off"
                               autocorrect="off"
                               spellcheck="false"
                               <#if credentialsRejected>aria-invalid="true" aria-describedby="kc-alert"</#if>/>
                    </div>
                </#if>

                <div class="lp-group">
                    <div class="lp-label-row">
                        <label for="password" class="lp-label">${msg("lpPasswordLabel")}</label>
                        <#-- Caps Lock is a state, and reporting a state is what
                             colour is for. Unhidden by js/login.js only while
                             the key is actually latched. -->
                        <span id="lp-caps" class="lp-caps" role="status" hidden>Caps Lock is on</span>
                    </div>

                    <div class="lp-inputgroup">
                        <input id="password" name="password" class="lp-input lp-input--reveal" type="password"
                               <#if usernameHidden??>autofocus</#if>
                               autocomplete="current-password"
                               <#if credentialsRejected>aria-invalid="true" aria-describedby="kc-alert"</#if>/>

                        <#-- Hidden until js/login.js wires it. A control that
                             cannot act must not render as though it can. -->
                        <button type="button" class="lp-reveal" data-password-toggle
                                aria-controls="password" aria-pressed="false"
                                aria-label="${msg('showPassword')}"
                                data-label-show="${msg('showPassword')}"
                                data-label-hide="${msg('hidePassword')}"
                                hidden>
                            <svg class="lp-reveal__show" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>
                            <svg class="lp-reveal__hide" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" hidden><path d="M9.88 9.88a3 3 0 1 0 4.24 4.24"/><path d="M10.73 5.08A10.43 10.43 0 0 1 12 5c7 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68"/><path d="M6.61 6.61A13.526 13.526 0 0 0 2 12s3 7 10 7a9.74 9.74 0 0 0 5.39-1.61"/><path d="m2 2 20 20"/></svg>
                        </button>
                    </div>
                </div>

                <div class="lp-buttons">
                    <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>

                    <#-- A <button> rather than <input type="submit"> so the busy
                         state can hold a spinner beside the label. `name` and
                         `value` still post, so the flow is unchanged. -->
                    <button type="submit" id="kc-login" name="login" value="${msg('doLogIn')}"
                            class="lp-btn lp-btn--primary lp-btn--block"
                            data-label-busy="${msg('lpSigningIn')}">
                        <span class="lp-btn__spinner" aria-hidden="true" hidden>
                            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>
                        </span>
                        <span class="lp-btn__label">${msg("doLogIn")}</span>
                    </button>
                </div>

                <#-- The whole row is conditional, not just its contents: an
                     empty flex row still spends vertical rhythm. Neither half
                     is switched on for any realm today. -->
                <#if (realm.rememberMe && !usernameHidden??) || realm.resetPasswordAllowed>
                    <div class="lp-options">
                        <#if realm.rememberMe && !usernameHidden??>
                            <div class="lp-check">
                                <input id="rememberMe" name="rememberMe" class="lp-check__input" type="checkbox" <#if login.rememberMe??>checked</#if>/>
                                <label for="rememberMe" class="lp-check__label">${msg("rememberMe")}</label>
                            </div>
                        <#else>
                            <span></span>
                        </#if>

                        <#if realm.resetPasswordAllowed>
                            <a class="lp-link" href="${url.loginResetCredentialsUrl}">${msg("doForgotPassword")}</a>
                        </#if>
                    </div>
                </#if>
            </form>
        </#if>

    <#elseif section = "socialProviders">
        <#-- Renders nothing today: no realm has an identity provider. Written
             anyway because the day one is added, a theme with no
             socialProviders section silently drops the only way in. -->
        <#if realm.password && social.providers??>
            <div id="kc-social-providers" class="lp-social">
                <p class="lp-social__label">${msg("identity-provider-login-label")}</p>
                <ul class="lp-social__list">
                    <#list social.providers as p>
                        <li>
                            <a id="social-${p.alias}" class="lp-btn lp-btn--outline" href="${p.loginUrl}">
                                <span class="lp-social__name">${p.displayName!}</span>
                            </a>
                        </li>
                    </#list>
                </ul>
            </div>
        </#if>

    <#elseif section = "info">
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div id="kc-registration">
                <span>${msg("noAccount")} <a class="lp-link" href="${url.registrationUrl}">${msg("doRegister")}</a></span>
            </div>
        </#if>
    </#if>

</@layout.registrationLayout>
