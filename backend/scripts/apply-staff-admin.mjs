#!/usr/bin/env node
/**
 * Creates or updates `staff.admin` -- one staff user holding every staff realm role, so
 * manual testing does not need a different login per screen.
 *
 * WHY THIS SCRIPT EXISTS
 * ----------------------
 * Same reason as apply-spa-client.sh: Keycloak's `--import-realm` only imports a realm
 * that does not already exist. This stack keeps Keycloak's data in Postgres
 * (KC_DB=postgres, volume infra_postgres-data), so on any environment started before,
 * editing keycloak/staff-realm.json has NO EFFECT and is skipped in silence. A fresh
 * volume (or CI) imports it correctly, which is what makes the trap easy to miss.
 *
 * WHY NODE AND NOT BASH
 * ---------------------
 * The sibling shell scripts need `jq`, which is not installed on the Windows dev machine
 * this platform is developed on -- apply-spa-client.sh fails there with "jq is required".
 * Node is already a hard dependency of the frontend toolchain, so this runs everywhere
 * the project already runs.
 *
 * WHAT THIS USER CAN AND CANNOT DO
 * --------------------------------
 * CAN: every endpoint gated by a staff realm role -- REALM_STAFF, FINANCE_OFFICER/ADMIN,
 * UNDERWRITER, CLAIMS_ASSESSOR and CLAIMS_MANAGER. It holds all of them.
 *
 * CANNOT, and deliberately:
 *
 *   1. Anything in the agents, customers or regulators realms. Those are separate
 *      Keycloak realms with their own issuers, and a staff token is not valid against
 *      them. Architectural boundary (PLAN.md section 3), not a role gap -- agent-facing
 *      screens still need agent.senior.
 *
 *   2. Assess a claim and then decide the same claim. ClaimsApiImpl enforces separation
 *      of duties on the *person*, not the role:
 *        "Separation of duties: <user> assessed this claim and cannot also decide it"
 *      That is a real control and this script does not defeat it. A full claim lifecycle
 *      still needs two identities: assess as staff.assessor, decide as staff.admin.
 *
 * Idempotent: creates the user if absent, resets the password and re-grants roles if
 * present. Reads the result back rather than trusting the write's status code.
 *
 * Usage:  node scripts/apply-staff-admin.mjs
 */

const KEYCLOAK_URL = process.env.KEYCLOAK_URL ?? 'http://localhost:8081';
const ADMIN_USER = process.env.KEYCLOAK_ADMIN ?? 'admin';
const ADMIN_PASSWORD = process.env.KEYCLOAK_ADMIN_PASSWORD ?? 'devadmin';
const REALM = process.env.REALM ?? 'staff';
const USERNAME = process.env.STAFF_ADMIN_USERNAME ?? 'staff.admin';
const PASSWORD = process.env.STAFF_ADMIN_PASSWORD ?? 'devpassword';
const TENANT_ID = process.env.TENANT_ID ?? '11111111-1111-1111-1111-111111111111';

const ROLES = [
  'ADMIN',
  'FINANCE_OFFICER',
  'UNDERWRITER',
  'CLAIMS_ASSESSOR',
  'CLAIMS_MANAGER',
  'CUSTOMER_SERVICE_REP',
];

const log = (msg) => console.log(`  ${msg}`);
const fail = (msg) => {
  console.error(`ERROR: ${msg}`);
  process.exit(1);
};

async function main() {
  log(`Authenticating against ${KEYCLOAK_URL} ...`);
  const tokenResponse = await fetch(
    `${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        client_id: 'admin-cli',
        username: ADMIN_USER,
        password: ADMIN_PASSWORD,
        grant_type: 'password',
      }),
    },
  ).catch((error) => fail(`could not reach Keycloak: ${error.message}`));

  if (!tokenResponse.ok) fail(`admin login failed with HTTP ${tokenResponse.status}`);
  const { access_token: token } = await tokenResponse.json();
  if (!token) fail('admin token was empty');

  const api = async (path, init = {}) => {
    const response = await fetch(`${KEYCLOAK_URL}/admin/realms/${REALM}${path}`, {
      ...init,
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        ...(init.headers ?? {}),
      },
    });
    if (!response.ok) {
      fail(`${init.method ?? 'GET'} ${path} failed with HTTP ${response.status}: ${await response.text()}`);
    }
    const text = await response.text();
    return text ? JSON.parse(text) : null;
  };

  const findUser = async () => {
    const [found] = await api(`/users?username=${encodeURIComponent(USERNAME)}&exact=true`);
    return found?.id ?? null;
  };

  let userId = await findUser();

  if (!userId) {
    log(`Creating ${USERNAME} in realm ${REALM} ...`);
    await api('/users', {
      method: 'POST',
      body: JSON.stringify({
        username: USERNAME,
        enabled: true,
        emailVerified: true,
        email: `${USERNAME}@example.tz`,
        firstName: 'Asha',
        lastName: 'Admin',
        attributes: { tenant_id: [TENANT_ID] },
      }),
    });
    userId = await findUser();
    if (!userId) fail('user was created but could not be read back');
  } else {
    log(`${USERNAME} already exists (${userId}); updating ...`);
    // tenant_id is what TenantContextFilter reads off the JWT. A user without it
    // authenticates fine and then fails every request, which is a confusing way to
    // discover the problem.
    await api(`/users/${userId}`, {
      method: 'PUT',
      body: JSON.stringify({ enabled: true, attributes: { tenant_id: [TENANT_ID] } }),
    });
  }

  log('Setting password ...');
  await api(`/users/${userId}/reset-password`, {
    method: 'PUT',
    body: JSON.stringify({ type: 'password', value: PASSWORD, temporary: false }),
  });

  log(`Granting roles: ${ROLES.join(', ')} ...`);
  const payload = [];
  for (const role of ROLES) {
    const representation = await api(`/roles/${encodeURIComponent(role)}`);
    if (!representation?.id) fail(`realm role ${role} does not exist in realm ${REALM}`);
    payload.push({ id: representation.id, name: representation.name });
  }
  await api(`/users/${userId}/role-mappings/realm`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });

  // Read it back rather than trusting the write's status code -- the discipline
  // apply-spa-client.sh adopted after a silent no-op, and the reason it caught one.
  const granted = (await api(`/users/${userId}/role-mappings/realm`)).map((r) => r.name);
  const missing = ROLES.filter((role) => !granted.includes(role));
  if (missing.length > 0) {
    fail(`the write reported success but the read disagrees -- missing: ${missing.join(', ')}`);
  }
  log(`Verified roles: ${[...granted].sort().join(', ')}`);

  console.log(`
  ${USERNAME} is ready. Sign in at http://localhost:5173/staff with password ${PASSWORD}.

  It CANNOT:
    - act in the agents, customers or regulators realms (separate Keycloak realms,
      separate issuers -- agent screens still need agent.senior)
    - assess AND decide the same claim (separation of duties is enforced on the
      person, not the role -- assess as staff.assessor, decide as staff.admin)
`);
}

await main();
