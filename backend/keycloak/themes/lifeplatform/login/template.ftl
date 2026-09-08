<#--
  The shell every Keycloak login-flow page renders inside.

  ## Why this file exists rather than 40 overridden templates

  It implements base's `registrationLayout` macro contract exactly -- the same
  parameters, and the same five nested sections ("header", "show-username",
  "form", "socialProviders", "info"). Every base template this theme does NOT
  override therefore renders inside this shell and inherits the design through
  the kc* class map in theme.properties. Overriding `login.ftl` alone gets the
  whole flow -- update password, page expired, TOTP, the error page, logout
  confirm -- rather than only the front door.

  Two parameters are added: `pageRealm` and `pageNote`. Base templates never
  pass them and get the empty default, so the contract stays
  backward-compatible. Both render as text through the auto-escaper; the markup
  around them lives here rather than being assembled in a caller's string,
  which is how a subtitle stops being an escaping hazard.

  ## The composition

  A pinned reference: two panels inside one rounded shell. Deep-green brand
  panel on the left carrying a figure and a display statement; white form panel
  on the right whose top-left corner takes a large radius so the green reads
  through that one corner. See resources/css/login.css for why this surface
  deliberately diverges from DESIGN.md's console world.

  ## Three departures from base, all deliberate

  1. Base renders the attempted username INSTEAD of the page title when
     `auth.showUsername()`, leaving those pages with no visible heading. Here
     the heading always renders and the attempted username follows it as an
     identity strip.
  2. Base's "try another way" is an <a> that submits a form through onclick.
     Here it is a real submit button, so it works without JavaScript and is
     announced as the action it is.
  3. Base loads its keyboard-menu script on every page. Here it loads only when
     the locale menu is actually rendered.
-->
<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false pageRealm="" pageNote="">

<#--
  The realm's human name where the administrator set one, its machine name
  otherwise. Never a hard-coded label: this console is white-label by
  requirement and four realms share this one theme, so the page states which
  realm it is opening from Keycloak's own data or not at all.
-->
<#assign realmLabel = (realm.displayName!'')?trim>
<#if realmLabel == ''>
    <#assign realmLabel = (realm.name)!''>
</#if>
<!DOCTYPE html>
<html class="lp-html"<#if realm.internationalizationEnabled> lang="${locale.currentLanguageTag}"<#else> lang="en"</#if>>
<head>
    <meta charset="utf-8"/>
    <meta name="robots" content="noindex, nofollow"/>
    <#if properties.meta?has_content>
        <#list properties.meta?split(' ') as meta>
    <meta name="${meta?split('==')[0]}" content="${meta?split('==')[1]}"/>
        </#list>
    </#if>
    <title>${msg("loginTitle", realmLabel)}</title>

    <link rel="icon" type="image/svg+xml" href="${url.resourcesPath}/img/favicon.svg"/>

    <#--
      Inter for the interface, Archivo Black for the brand panel's display
      voice. `display=swap` keeps the render non-blocking, and the CSS declares
      a real fallback stack behind each, so a blocked font CDN costs the
      typefaces and nothing else.
    -->
    <link rel="preconnect" href="https://fonts.googleapis.com"/>
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin/>
    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=Archivo+Black&display=swap"/>

    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
    <link href="${url.resourcesPath}/${style}" rel="stylesheet"/>
        </#list>
    </#if>
    <#if properties.scripts?has_content>
        <#list properties.scripts?split(' ') as script>
    <script src="${url.resourcesPath}/${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <#if scripts??>
        <#list scripts as script>
    <script src="${script}" type="text/javascript"></script>
        </#list>
    </#if>

    <#if realm.internationalizationEnabled && locale.supported?size gt 1>
    <script src="${url.resourcesPath}/js/menu-button-links.js" type="module"></script>
    </#if>

    <#-- Base's cookie check and cross-tab SSO watcher. Real functionality, kept
         as base wrote it: it is what tells a user with cookies blocked why the
         sign-in cannot proceed. -->
    <#if authenticationSession??>
    <script type="module">
        import { checkCookiesAndSetTimer } from "${url.resourcesPath}/js/authChecker.js";

        checkCookiesAndSetTimer(
          "${authenticationSession.authSessionId}",
          "${authenticationSession.tabId}",
          "${url.ssoLoginInOtherTabsUrl?no_esc}"
        );
    </script>
    </#if>
</head>

<body class="lp-body<#if bodyClass?has_content> ${bodyClass}</#if>">
<div class="lp-shell">

    <aside class="lp-brand">
        <#-- The figure bleeds off the panel's left edge. It carries the brand
             photograph when resources/img/brand.jpg exists, and is a flat field
             of brand green under the lime disc when it does not. -->
        <div class="lp-brand__figure">
            <span class="lp-brand__disc" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14"/><path d="m12 5 7 7-7 7"/></svg>
            </span>
        </div>

        <div class="lp-brand__say">
            <#-- The platform's own position, which is the one thing a competitor
                 could not truthfully copy: no analytics endpoints exist, so
                 nothing on this platform is rendered that it cannot prove. -->
            <p class="lp-brand__display">Every figure, provable</p>
            <p class="lp-brand__body">
                Core administration for life assurance in Tanzania &mdash; underwriting
                through settlement, general ledger through statutory return.
            </p>
        </div>
    </aside>

    <main class="lp-panel">
        <#if realm.internationalizationEnabled && locale.supported?size gt 1>
            <div class="lp-locale" id="kc-locale">
                <div class="lp-locale__wrap" id="kc-locale-wrapper">
                    <div class="menu-button-links lp-locale__dd" id="kc-locale-dropdown">
                        <button type="button" id="kc-current-locale-link" class="lp-locale__button"
                                aria-label="${msg("languages")}" aria-haspopup="true" aria-expanded="false"
                                aria-controls="language-switch1">${locale.current}</button>
                        <ul role="menu" tabindex="-1" aria-labelledby="kc-current-locale-link"
                            aria-activedescendant="" id="language-switch1" class="lp-locale__list">
                            <#assign i = 1>
                            <#list locale.supported as l>
                                <li class="lp-locale__li" role="none">
                                    <a role="menuitem" id="language-${i}" class="lp-locale__item" href="${l.url}">${l.label}</a>
                                </li>
                                <#assign i++>
                            </#list>
                        </ul>
                    </div>
                </div>
            </div>
        </#if>

        <div class="lp-col">
            <#-- The mark, authored as geometry rather than shipped as a bitmap,
                 so it stays crisp from 16px favicon to this 2.5rem lockup and
                 takes its colour from a token. -->
            <p class="lp-mark">
                <svg viewBox="4 1 40 40" width="44" height="44" role="img" aria-label="Nlolo">
                    <path d="M10 35V12l22 23V20" fill="none" stroke="currentColor" stroke-width="6.5" stroke-linecap="round" stroke-linejoin="round"/>
                    <path fill="currentColor" fill-rule="evenodd" d="M32 19c.5-8 4-13.5 9.5-16 1 6.5-2 13.5-9.5 16Zm1.5-1.8c1-4.7 3.3-8.8 6.3-12-3.4 2.6-5.6 6.8-6.3 12Z"/>
                </svg>
            </p>

            <#if displayRequiredFields>
                <p class="lp-required"><span aria-hidden="true">*</span> ${msg("requiredFields")}</p>
            </#if>

            <h1 id="kc-page-title" class="lp-title"><#nested "header"></h1>

            <#if pageRealm?has_content>
                <#-- Which of the four realms this is. Genuinely operational
                     rather than decorative: the same credentials do not exist
                     in two of them, and somebody sent the agents URL needs to
                     see that before typing. -->
                <p class="lp-sub">Signing in to <b>${pageRealm}</b></p>
            </#if>

            <#if auth?has_content && auth.showUsername() && !auth.showResetCredentials()>
                <#nested "show-username">
                <div id="kc-username" class="lp-attempted">
                    <span class="lp-attempted__cap">Signing in as</span>
                    <span id="kc-attempted-username" class="lp-attempted__val">${auth.attemptedUsername}</span>
                    <a id="reset-login" class="lp-link lp-attempted__reset" href="${url.loginRestartFlowUrl}">${msg("restartLoginTooltip")}</a>
                </div>
            </#if>

            <div id="kc-content">
                <div id="kc-content-wrapper">

                    <#-- App-initiated actions should not see the warning about
                         needing to complete the action during login: base's own
                         condition, preserved. -->
                    <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
                        <div id="kc-alert" class="lp-alert lp-alert--${message.type}"
                             <#if message.type = 'error'>role="alert"<#else>role="status"</#if>>
                            <span class="lp-alert__icon" aria-hidden="true">
                                <#if message.type = 'error'>
                                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/></svg>
                                <#elseif message.type = 'warning'>
                                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 8v4"/><path d="M12 16h.01"/></svg>
                                <#elseif message.type = 'success'>
                                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/></svg>
                                <#else>
                                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg>
                                </#if>
                            </span>
                            <span class="lp-alert__text">${kcSanitize(message.summary)?no_esc}</span>
                        </div>
                    </#if>

                    <#nested "form">

                    <#if auth?has_content && auth.showTryAnotherWayLink()>
                        <form id="kc-select-try-another-way-form" class="lp-tryanother" action="${url.loginAction}" method="post">
                            <input type="hidden" name="tryAnotherWay" value="on"/>
                            <button type="submit" id="try-another-way" class="lp-btn lp-btn--outline lp-btn--block">${msg("doTryAnotherWay")}</button>
                        </form>
                    </#if>

                    <#nested "socialProviders">

                    <#if displayInfo>
                        <div id="kc-info" class="lp-info">
                            <div id="kc-info-wrapper">
                                <#nested "info">
                            </div>
                        </div>
                    </#if>

                    <#if pageNote?has_content>
                        <#-- The reference gives this region to third-party
                             sign-in buttons. No identity provider is
                             configured on any realm here, so rather than leave
                             a hole or fake three dead buttons, it carries the
                             one thing a person on a page with no "sign up" and
                             no "forgot password" actually needs to know. -->
                        <p class="lp-note">${pageNote}</p>
                    </#if>
                </div>
            </div>
        </div>
    </main>

</div>
</body>
</html>
</#macro>
