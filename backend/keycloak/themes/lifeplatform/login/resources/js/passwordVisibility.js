/*
  Deliberately a no-op, and deliberately named after base's own file.

  Base's `login-update-password.ftl`, `login-reset-password.ftl` and their
  siblings end with a hard-coded

      <script type="module" src="${url.resourcesPath}/js/passwordVisibility.js">

  which this theme cannot remove without overriding every one of those
  templates. Keycloak resolves theme resources from the theme's own directory
  first and only then walks the parent chain, so a file of this name here
  shadows base's and the tag loads nothing.

  Why it has to be shadowed rather than tolerated: base's version wires the same
  `[data-password-toggle]` contract that `login.js` does, so both handlers fired
  on one click and toggled `input.type` twice -- the reveal control on every
  inherited password field looked live and did nothing. It also assumed the
  button contains an <i> whose className it may rewrite, and this theme replaces
  that with an authored SVG, so it threw

      Cannot set property className of #<SVGElement> which has only a getter

  on page load. Measured on the forced-password-update page, not inferred.

  `login.js` implements the same contract for every page in the flow, including
  the ones this theme does not author.
*/
