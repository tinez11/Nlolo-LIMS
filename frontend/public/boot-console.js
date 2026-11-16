// Which console, from the address -- the same words SessionSplash shows next, so nothing on screen moves. A file, not
// an inline script, so the production content security policy can forbid inline scripts (2026-11-16).
(function () {
  var names = { staff: 'Staff', agents: 'Agent', customers: 'Policyholder', regulators: 'Regulator' };
  var name = names[location.pathname.split('/')[1]];
  var line = document.getElementById('boot-console');
  if (name && line) line.textContent = name + ' console';
})();
