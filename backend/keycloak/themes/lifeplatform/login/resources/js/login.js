/*
  Progressive enhancement for the Life Platform login theme.

  Everything here is additive: with JavaScript off the form still posts, the
  password field still accepts a password, and nothing on the page is hidden
  behind a script. The three things it adds are the three things a login page
  genuinely needs and Keycloak's stock theme either lacks or gets wrong.

  Base's own passwordVisibility.js is deliberately NOT loaded -- it swaps Font
  Awesome classes on an <i>, and this theme has no icon font. The
  `data-password-toggle` contract it reads is honoured here instead, so the
  password pages this theme inherits rather than authors get a working, correctly
  drawn toggle too.
*/
(function () {
  'use strict';

  // This file is loaded in <head> without `defer`, so <body> does not exist yet.
  // Only the root element is reachable, and the class is set here rather than on
  // DOMContentLoaded so a control that needs JavaScript is never painted before
  // it works.
  document.documentElement.classList.add('lp-js');

  // Lucide geometry, in the console's icon idiom: 24px viewBox, 2px stroke,
  // round caps and joins, currentColor, no fill.
  var ICON_SHOW =
    '<svg class="lp-reveal__show" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>';
  var ICON_HIDE =
    '<svg class="lp-reveal__hide" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" hidden>' +
    '<path d="M9.88 9.88a3 3 0 1 0 4.24 4.24"/>' +
    '<path d="M10.73 5.08A10.43 10.43 0 0 1 12 5c7 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68"/>' +
    '<path d="M6.61 6.61A13.526 13.526 0 0 0 2 12s3 7 10 7a9.74 9.74 0 0 0 5.39-1.61"/>' +
    '<path d="m2 2 20 20"/></svg>';

  function ready(fn) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', fn);
    } else {
      fn();
    }
  }

  /**
   * Reveal / conceal, on a control that renders only once it can act -- the same
   * rule this console applies to an endpoint that is not implemented yet.
   */
  function wireReveal(button) {
    var input = document.getElementById(button.getAttribute('aria-controls') || 'password');
    if (!input) {
      return;
    }

    var show = button.querySelector('.lp-reveal__show');
    var hide = button.querySelector('.lp-reveal__hide');

    // An inherited base template rendered an icon-font <i> rather than this
    // theme's authored SVG. Replace it, so every password field on every page
    // carries the same icon at the same stroke weight.
    if (!show || !hide) {
      button.innerHTML = ICON_SHOW + ICON_HIDE;
      button.classList.add('lp-reveal');
      show = button.querySelector('.lp-reveal__show');
      hide = button.querySelector('.lp-reveal__hide');
    }

    button.hidden = false;

    button.addEventListener('click', function () {
      var wasRevealed = input.type === 'text';
      input.type = wasRevealed ? 'password' : 'text';

      show.hidden = !wasRevealed;
      hide.hidden = wasRevealed;
      button.setAttribute('aria-pressed', wasRevealed ? 'false' : 'true');

      var label = button.getAttribute(wasRevealed ? 'data-label-show' : 'data-label-hide');
      if (label) {
        button.setAttribute('aria-label', label);
      }
    });
  }

  /**
   * Caps Lock. The commonest reason a correct password is rejected, and the one
   * a rejection message can never name because the server cannot see it.
   */
  function wireCapsLock(input, note) {
    function update(event) {
      if (typeof event.getModifierState !== 'function') {
        return;
      }
      note.hidden = !event.getModifierState('CapsLock');
    }

    input.addEventListener('keydown', update);
    input.addEventListener('keyup', update);
    input.addEventListener('blur', function () {
      note.hidden = true;
    });
  }

  /**
   * One submit per form, and a button that says it is working.
   *
   * The POST is a full navigation, so on a slow link the page sits inert for
   * seconds -- which is exactly when an operator clicks again and Keycloak
   * answers the second attempt with an expired-page error. The button is left
   * ENABLED on purpose: disabling it would drop its own `login` parameter from
   * the request, which is the bug in base's inline `onsubmit` handler.
   */
  function wireSubmitGuard(form) {
    var submitting = false;
    var button = form.querySelector('button[type="submit"]');
    var spinner = button && button.querySelector('.lp-btn__spinner');
    var label = button && button.querySelector('.lp-btn__label');
    var idleText = label ? label.textContent : null;

    function reset() {
      submitting = false;
      if (spinner) {
        spinner.hidden = true;
      }
      if (label && idleText !== null) {
        label.textContent = idleText;
      }
      if (button) {
        button.removeAttribute('aria-busy');
      }
    }

    form.addEventListener('submit', function (event) {
      if (submitting) {
        event.preventDefault();
        return;
      }
      submitting = true;

      if (!button) {
        return;
      }
      if (spinner) {
        spinner.hidden = false;
      }
      var busyText = button.getAttribute('data-label-busy');
      if (label && busyText) {
        label.textContent = busyText;
      }
      button.setAttribute('aria-busy', 'true');
    });

    // Restored from the back/forward cache with the DOM as it was left: without
    // this the button comes back mid-submit and refuses the next click.
    window.addEventListener('pageshow', function (event) {
      if (event.persisted) {
        reset();
      }
    });
  }

  ready(function () {
    var toggles = document.querySelectorAll('[data-password-toggle]');
    for (var i = 0; i < toggles.length; i++) {
      wireReveal(toggles[i]);
    }

    var password = document.getElementById('password');
    var caps = document.getElementById('lp-caps');
    if (password && caps) {
      wireCapsLock(password, caps);
    }

    var guarded = document.querySelectorAll('form[data-guard-submit]');
    for (var j = 0; j < guarded.length; j++) {
      wireSubmitGuard(guarded[j]);
    }
  });
})();
