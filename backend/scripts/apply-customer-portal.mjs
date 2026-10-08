#!/usr/bin/env node
/**
 * Sets up the customers realm for the customer portal (2026-10-08, the customer portal design step 1) on a Keycloak
 * that already exists.
 *
 * Same reason as apply-staff-admin.mjs: `--import-realm` only imports a realm that does not exist yet, and this stack
 * keeps Keycloak's data in Postgres, so editing keycloak/customers-realm.json changes nothing on a running dev stack.
 * This applies the same settings through the Admin API:
 *
 *   1. lifeplatform-spa -- the console's public PKCE client, mapping tenant_id and party_id. Without it a customer
 *      cannot sign in from the browser at all.
 *   2. lifeplatform-admin -- a confidential client with a service account holding manage-users and view-users in this
 *      realm only. The backend creates customer logins through it when staff invite a client.
 *   3. Realm: password reset allowed, password policy, lockout after failed attempts, the mail server (mailpit in dev).
 *
 * Idempotent: creates what is missing, updates what exists, then proves the service account can obtain a token.
 *
 * Usage:  node scripts/apply-customer-portal.mjs
 */

const KEYCLOAK_URL = process.env.KEYCLOAK_URL ?? 'http://localhost:8081';
const ADMIN_USER = process.env.KEYCLOAK_ADMIN ?? 'admin';
const ADMIN_PASSWORD = process.env.KEYCLOAK_ADMIN_PASSWORD ?? 'devadmin';
const REALM = 'customers';
const SPA_ORIGIN = process.env.SPA_ORIGIN ?? 'http://localhost:5173';
const ADMIN_SECRET = process.env.KEYCLOAK_PORTAL_ADMIN_SECRET ?? 'dev-secret-portal-admin';
const SMTP_HOST = process.env.SMTP_HOST ?? 'mailpit';
const SMTP_PORT = process.env.SMTP_PORT ?? '1025';

const log = (msg) => console.log(`  ${msg}`);
const fail = (msg) => {
  console.error(`ERROR: ${msg}`);
  process.exit(1);
};

const mapper = (name) => ({
  name,
  protocol: 'openid-connect',
  protocolMapper: 'oidc-usermodel-attribute-mapper',
  consentRequired: false,
  config: {
    'user.attribute': name,
    'claim.name': name,
    'jsonType.label': 'String',
    'id.token.claim': 'true',
    'access.token.claim': 'true',
    'userinfo.token.claim': 'true',
  },
});

const SPA = {
  clientId: 'lifeplatform-spa',
  name: 'Life Platform SPA (browser, PKCE)',
  enabled: true,
  publicClient: true,
  protocol: 'openid-connect',
  standardFlowEnabled: true,
  implicitFlowEnabled: false,
  directAccessGrantsEnabled: false,
  serviceAccountsEnabled: false,
  redirectUris: [`${SPA_ORIGIN}/*`],
  webOrigins: [SPA_ORIGIN],
  attributes: { 'pkce.code.challenge.method': 'S256', 'post.logout.redirect.uris': `${SPA_ORIGIN}/*` },
};

const ADMIN_CLIENT = {
  clientId: 'lifeplatform-admin',
  name: 'Life Platform portal admin (service account)',
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

  log('Realm settings: password reset, password policy, lockout, mail server ...');
  const realm = await api('');
  await api('', {
    method: 'PUT',
    body: JSON.stringify({
      ...realm,
      resetPasswordAllowed: true,
      passwordPolicy: 'length(8) and notUsername(undefined)',
      bruteForceProtected: true,
      failureFactor: 5,
      waitIncrementSeconds: 60,
      maxFailureWaitSeconds: 900,
      smtpServer: { host: SMTP_HOST, port: SMTP_PORT, from: 'no-reply@nlolo.local', fromDisplayName: 'Nlolo Life' },
    }),
  });

  const upsertClient = async (wanted) => {
    const [existing] = await api(`/clients?clientId=${encodeURIComponent(wanted.clientId)}`);
    if (existing) {
      log(`${wanted.clientId} exists; updating ...`);
      await api(`/clients/${existing.id}`, { method: 'PUT', body: JSON.stringify({ ...existing, ...wanted }) });
      return existing.id;
    }
    log(`Creating ${wanted.clientId} ...`);
    await api('/clients', { method: 'POST', body: JSON.stringify(wanted) });
    const [created] = await api(`/clients?clientId=${encodeURIComponent(wanted.clientId)}`);
    if (!created) fail(`${wanted.clientId} was created but could not be read back`);
    return created.id;
  };

  const spaId = await upsertClient(SPA);
  const mappers = (await api(`/clients/${spaId}/protocol-mappers/models`)).map((m) => m.name);
  for (const name of ['tenant_id', 'party_id']) {
    if (!mappers.includes(name)) {
      log(`Adding the ${name} mapper to lifeplatform-spa ...`);
      await api(`/clients/${spaId}/protocol-mappers/models`, { method: 'POST', body: JSON.stringify(mapper(name)) });
    }
  }

  const adminId = await upsertClient(ADMIN_CLIENT);
  const serviceAccount = await api(`/clients/${adminId}/service-account-user`);
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
  console.log('Done: the customers realm is ready for the portal.');
}

main().catch((error) => fail(error.stack ?? String(error)));
