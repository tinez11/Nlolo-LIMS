#!/usr/bin/env node
/**
 * Sets up the staff realm so administrators can add, close and reset staff logins from the console's Users screen
 * (2026-10-26) on a Keycloak that already exists.
 *
 * Same reason as apply-customer-portal.mjs: `--import-realm` only imports a realm that does not exist yet, and this
 * stack keeps Keycloak's data in Postgres, so editing keycloak/staff-realm.json changes nothing on a running stack.
 *
 *   1. lifeplatform-staff-admin -- a confidential client with a service account holding manage-users and view-users
 *      in the staff realm only. The backend creates, opens, closes and resets staff logins through it.
 *   2. Realm: password policy and lockout after failed attempts, so a temporary password is changed and guessed
 *      passwords stop.
 *
 * Roles are NOT written to Keycloak: the platform holds them (iam). Idempotent; ends by proving the service account
 * can obtain a token.
 *
 * Usage:  node scripts/apply-staff-user-admin.mjs
 */

const KEYCLOAK_URL = process.env.KEYCLOAK_URL ?? 'http://localhost:8081';
const ADMIN_USER = process.env.KEYCLOAK_ADMIN ?? 'admin';
const ADMIN_PASSWORD = process.env.KEYCLOAK_ADMIN_PASSWORD ?? 'devadmin';
const REALM = 'staff';
const ADMIN_SECRET = process.env.KEYCLOAK_STAFF_ADMIN_SECRET ?? 'dev-secret-staff-admin';

const log = (msg) => console.log(`  ${msg}`);
const fail = (msg) => {
  console.error(`ERROR: ${msg}`);
  process.exit(1);
};

const ADMIN_CLIENT = {
  clientId: 'lifeplatform-staff-admin',
  name: 'Life Platform staff user admin (service account)',
  description: 'The backend adds, closes and resets staff logins through this service account (manage-users in this '
    + 'realm only) when an administrator uses the Users screen. No browser flows.',
  enabled: true,
  publicClient: false,
  protocol: 'openid-connect',
  standardFlowEnabled: false,
  implicitFlowEnabled: false,
  directAccessGrantsEnabled: false,
  serviceAccountsEnabled: true,
  secret: ADMIN_SECRET,
};

async function main() {
  log(`Authenticating against ${KEYCLOAK_URL} ...`);
  const tokenResponse = await fetch(`${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: 'admin-cli', username: ADMIN_USER, password: ADMIN_PASSWORD, grant_type: 'password' }),
  }).catch((error) => fail(`could not reach Keycloak: ${error.message}`));
  if (!tokenResponse.ok) fail(`admin login failed with HTTP ${tokenResponse.status}`);
  const { access_token: token } = await tokenResponse.json();

  const api = async (path, init = {}) => {
    const response = await fetch(`${KEYCLOAK_URL}/admin/realms/${REALM}${path}`, {
      ...init,
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...(init.headers ?? {}) },
    });
    if (!response.ok) fail(`${init.method ?? 'GET'} ${path || '/'} failed with HTTP ${response.status}: ${await response.text()}`);
    const text = await response.text();
    return text ? JSON.parse(text) : null;
  };

  log('Realm settings: password policy and lockout ...');
  const realm = await api('');
  await api('', {
    method: 'PUT',
    body: JSON.stringify({
      ...realm,
      passwordPolicy: realm.passwordPolicy || 'length(8) and notUsername(undefined)',
      bruteForceProtected: true,
      failureFactor: 5,
      waitIncrementSeconds: 60,
      maxFailureWaitSeconds: 900,
    }),
  });

  const [existing] = await api(`/clients?clientId=${encodeURIComponent(ADMIN_CLIENT.clientId)}`);
  let clientId;
  if (existing) {
    log(`${ADMIN_CLIENT.clientId} exists; updating ...`);
    await api(`/clients/${existing.id}`, { method: 'PUT', body: JSON.stringify({ ...existing, ...ADMIN_CLIENT }) });
    clientId = existing.id;
  } else {
    log(`Creating ${ADMIN_CLIENT.clientId} ...`);
    await api('/clients', { method: 'POST', body: JSON.stringify(ADMIN_CLIENT) });
    const [created] = await api(`/clients?clientId=${encodeURIComponent(ADMIN_CLIENT.clientId)}`);
    if (!created) fail(`${ADMIN_CLIENT.clientId} was created but could not be read back`);
    clientId = created.id;
  }

  const serviceAccount = await api(`/clients/${clientId}/service-account-user`);
  const [realmManagement] = await api('/clients?clientId=realm-management');
  if (!realmManagement) fail('realm-management client not found');
  const roles = await api(`/clients/${realmManagement.id}/roles`);
  const wanted = roles.filter((r) => r.name === 'manage-users' || r.name === 'view-users');
  if (wanted.length !== 2) fail('manage-users / view-users roles not found');
  log('Granting manage-users and view-users to the service account ...');
  await api(`/users/${serviceAccount.id}/role-mappings/clients/${realmManagement.id}`, {
    method: 'POST',
    body: JSON.stringify(wanted),
  });

  log('Proving the service account can sign in ...');
  const probe = await fetch(`${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'client_credentials', client_id: ADMIN_CLIENT.clientId, client_secret: ADMIN_SECRET }),
  });
  if (!probe.ok) fail(`the service account could not obtain a token (HTTP ${probe.status})`);
  console.log('Done: the staff realm is ready for the Users screen.');
}

main().catch((error) => fail(error.stack ?? String(error)));
