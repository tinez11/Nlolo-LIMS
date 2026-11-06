// The production realm (2026-10-13, deployment pilot §3; one realm since 2026-11-06), checked against the shape the
// design promised. These assertions are the reason the generator exists: a hand-maintained production copy of a realm
// file drifts from the dev one silently, and the drift only shows as a login nobody can perform.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readdirSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildAll } from './build-realms.mjs';

const out = mkdtempSync(join(tmpdir(), 'realms-'));
buildAll(out);
const text = readFileSync(join(out, 'lifeplatform.json'), 'utf8');
const realm = JSON.parse(text);
const client = (id) => realm.clients.find((c) => c.clientId === id);

test('one realm, named lifeplatform, and nothing else written', () => {
  assert.deepEqual(readdirSync(out), ['lifeplatform.json']);
  assert.equal(realm.realm, 'lifeplatform');
});

test('no dev users and no dev secrets anywhere', () => {
  assert.doesNotMatch(text, /devpassword|dev-secret|localhost|mailpit/);
  assert.deepEqual(realm.users.map((u) => u.username), ['service-account-lifeplatform-admin']);
});

test('the SPA client is on APP_DOMAIN only, and there is no password-grant client', () => {
  assert.equal(client('lifeplatform-app'), undefined);
  const spa = client('lifeplatform-spa');
  assert.deepEqual(spa.redirectUris, ['https://$(env:APP_DOMAIN)/*']);
  assert.deepEqual(spa.webOrigins, ['https://$(env:APP_DOMAIN)']);
  assert.equal(spa.publicClient, true);
  assert.equal(spa.attributes['pkce.code.challenge.method'], 'S256');
  assert.deepEqual(spa.protocolMappers.map((m) => m.name).sort(), ['home_branch', 'party_id', 'tenant_id']);
});

test('the one admin client takes its secret from the environment', () => {
  assert.equal(client('lifeplatform-admin').secret, '$(env:ADMIN_CLIENT_SECRET)');
});

test('sign-in by username or email, and emails unique', () => {
  assert.equal(realm.loginWithEmailAllowed, true);
  assert.equal(realm.duplicateEmailsAllowed, false);
});

test('password policy, lockout and SMTP settings', () => {
  assert.equal(realm.passwordPolicy, 'length(8) and notUsername(undefined) and notEmail(undefined) and passwordHistory(3)');
  assert.equal(realm.bruteForceProtected, true);
  assert.equal(realm.sslRequired, 'external');
  assert.equal(realm.resetPasswordAllowed, '$(env:MAIL_ENABLED)');
  assert.equal(realm.smtpServer.host, '$(env:MAIL_HOST)');
});

test('the workspace roles and every role the platform authorizes on survive', () => {
  assert.deepEqual(realm.roles.realm.map((r) => r.name).sort(), ['ADMIN', 'CLAIMS_ASSESSOR', 'CLAIMS_MANAGER',
    'CUSTOMER_SERVICE_REP', 'FINANCE_APPROVER', 'FINANCE_OFFICER', 'SENIOR_UNDERWRITER', 'TIRA_READ_ONLY',
    'UNDERWRITER', 'agent', 'customer', 'regulator', 'staff']);
});
