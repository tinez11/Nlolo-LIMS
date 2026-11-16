// Applied before first paint so a dark-mode reload never flashes white. A file, not an inline script, so the
// production content security policy can forbid inline scripts outright (2026-11-16).
// Wrapped because storage access itself throws in some privacy modes.
try {
  var stored = localStorage.getItem('lifeplatform.theme');
  var dark = stored === 'dark' || (stored !== 'light' && matchMedia('(prefers-color-scheme: dark)').matches);
  if (dark) document.documentElement.classList.add('dark');
} catch (e) {
  /* no stored preference reachable; fall through to the light default */
}
