// The production realms (2026-10-13, deployment pilot §3), checked against the shape the design promised. These
// assertions are the reason the generator exists: a hand-maintained production copy of four realm files drifts from
// the dev ones silently, and the drift only shows as a login nobody can perform.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildAll } from './build-realms.mjs';

const out = mkdtempSync(join(tmpdir(), 'realms-'));
buildAll(out);
const realm = (name) => JSON.parse(readFileSync(join(out, `${name}.json`), 'utf8'));
const REALMS = ['staff', 'agents', 'customers', 'regulators'];

test('no dev users and no dev secrets anywhere', () => {
  for (const name of REALMS) {
    const text = readFileSync(join(out, `${name}.json`), 'utf8');
    assert.doesNotMatch(text, /devpassword|dev-secret|localhost|mailpit/, name);
    const humans = (realm(name).users ?? []).filter((u) => !u.username.startsWith('service-account-'));
    assert.deepEqual(humans, [], name);
  }
});

test('every realm has the SPA client on APP_DOMAIN only, and no password-grant client', () => {
  for (const name of REALMS) {
    const clients = realm(name).clients;
    assert.equal(clients.find((c) => c.clientId === 'lifeplatform-app'), undefined, name);
    const spa = clients.find((c) => c.clientId === 'lifeplatform-spa');
    assert.ok(spa, `${name} has lifeplatform-spa`);
    assert.deepEqual(spa.redirectUris, ['https://$(env:APP_DOMAIN)/*'], name);
    assert.deepEqual(spa.webOrigins, ['https://$(env:APP_DOMAIN)'], name);
    assert.equal(spa.publicClient, true, name);
    assert.equal(spa.attributes['pkce.code.challenge.method'], 'S256', name);
    assert.ok(spa.protocolMappers.some((m) => m.name === 'tenant_id'), `${name} maps tenant_id`);
  }
});

test('regulators gain the client they lacked, mapping tenant_id only', () => {
  const spa = realm('regulators').clients.find((c) => c.clientId === 'lifeplatform-spa');
  assert.deepEqual(spa.protocolMappers.map((m) => m.name), ['tenant_id']);
});

test('staff keep home_branch and customers keep party_id', () => {
  const mappers = (name) =>
    realm(name).clients.find((c) => c.clientId === 'lifeplatform-spa').protocolMappers.map((m) => m.name).sort();
  assert.deepEqual(mappers('staff'), ['home_branch', 'tenant_id']);
  assert.deepEqual(mappers('customers'), ['party_id', 'tenant_id']);
  assert.deepEqual(mappers('agents'), ['party_id', 'tenant_id']);
});

test('the staff user admin client takes its secret from the environment', () => {
  const admin = realm('staff').clients.find((c) => c.clientId === 'lifeplatform-staff-admin');
  assert.equal(admin.secret, '$(env:STAFF_ADMIN_CLIENT_SECRET)');
  assert.ok(realm('staff').users.some((u) => u.username === 'service-account-lifeplatform-staff-admin'));
});

test('the portal admin client takes its secret from the environment', () => {
  const admin = realm('customers').clients.find((c) => c.clientId === 'lifeplatform-admin');
  assert.equal(admin.secret, '$(env:PORTAL_ADMIN_CLIENT_SECRET)');
  assert.ok(realm('customers').users.some((u) => u.username === 'service-account-lifeplatform-admin'));
});

test('password policy, lockout and SMTP settings on every realm', () => {
  for (const name of REALMS) {
    const r = realm(name);
    assert.equal(r.passwordPolicy, 'length(10) and notUsername(undefined) and notEmail(undefined) and passwordHistory(3)', name);
    assert.equal(r.bruteForceProtected, true, name);
    assert.equal(r.sslRequired, 'external', name);
    assert.equal(r.resetPasswordAllowed, '$(env:MAIL_ENABLED)', name);
    assert.equal(r.smtpServer.host, '$(env:MAIL_HOST)', name);
  }
});

test('the roles the platform authorizes on survive unchanged', () => {
  const names = (name) => (realm(name).roles?.realm ?? []).map((r) => r.name).sort();
  assert.deepEqual(names('staff'), ['ADMIN', 'CLAIMS_ASSESSOR', 'CLAIMS_MANAGER', 'CUSTOMER_SERVICE_REP',
    'FINANCE_APPROVER', 'FINANCE_OFFICER', 'SENIOR_UNDERWRITER', 'UNDERWRITER']);
  assert.deepEqual(names('regulators'), ['TIRA_READ_ONLY']);
});
