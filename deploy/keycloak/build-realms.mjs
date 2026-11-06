#!/usr/bin/env node
/**
 * Generates the production realm from the dev one (2026-10-13, deployment pilot §3; one realm since 2026-11-06).
 *
 * ONE SOURCE. The spec called for a hand-written production copy plus a test asserting the two agree. Generating it
 * instead removes the thing the test would have been guarding against: there is no second copy to drift. Roles,
 * clients and token mappers come from backend/keycloak/lifeplatform-realm.json -- the file dev already proves every day
 * -- and only what production must differ in is applied here.
 *
 * What production changes:
 *   - every dev user and every dev secret is dropped (keycloak-config-cli's IMPORT_MANAGED_USER=no-delete leaves the
 *     real users it never created alone)
 *   - lifeplatform-spa points at APP_DOMAIN alone
 *   - lifeplatform-app, the confidential password-grant client pointing at localhost:3000, is left out entirely
 *   - a password policy and brute-force lockout (the user's D5; no second factor yet)
 *   - SMTP and self-service password reset, both driven by MAIL_ENABLED
 *
 * Output carries keycloak-config-cli's `$(env:NAME)` tokens, substituted on the server from its .env.
 *
 * Usage: node deploy/keycloak/build-realms.mjs <outDir>
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const DEV = resolve(here, '../../backend/keycloak/lifeplatform-realm.json');
const APP = 'https://$(env:APP_DOMAIN)';

/** Keycloak assigns its own ids; carrying dev's would tie two unrelated installations together. */
function withoutId(object) {
  const copy = structuredClone(object);
  delete copy.id;
  return copy;
}

function productionSpa(client) {
  const spa = withoutId(client);
  spa.redirectUris = [`${APP}/*`];
  spa.webOrigins = [APP];
  spa.attributes = { ...(spa.attributes ?? {}), 'post.logout.redirect.uris': `${APP}/*` };
  spa.protocolMappers = (spa.protocolMappers ?? []).map(withoutId);
  return spa;
}

export function buildRealm(realm) {
  const r = structuredClone(realm);

  // Dev users are dev's. The service account belongs to its client, not to a person, and must stay.
  r.users = (r.users ?? []).filter((u) => u.username.startsWith('service-account-'));

  r.clients = (r.clients ?? []).filter((c) => c.clientId !== 'lifeplatform-app');
  delete r.roles?.client?.['lifeplatform-app'];
  if (r.clientScopeMappings) delete r.clientScopeMappings['lifeplatform-app'];
  if (r.scopeMappings) r.scopeMappings = r.scopeMappings.filter((m) => m.client !== 'lifeplatform-app');

  r.clients = r.clients.map((c) => (c.clientId === 'lifeplatform-spa' ? productionSpa(c) : c));
  for (const client of r.clients) {
    if (client.clientId === 'lifeplatform-admin') {
      client.secret = '$(env:ADMIN_CLIENT_SECRET)';
    }
  }

  r.sslRequired = 'external';
  r.passwordPolicy = 'length(8) and notUsername(undefined) and notEmail(undefined) and passwordHistory(3)';
  r.bruteForceProtected = true;
  r.permanentLockout = false;        // a locked-out underwriter at month end is its own outage
  r.failureFactor = 5;
  r.waitIncrementSeconds = 60;
  r.maxFailureWaitSeconds = 900;

  // Self-service reset needs mail to exist; until MAIL_ENABLED is true an admin resets a password instead.
  r.resetPasswordAllowed = '$(env:MAIL_ENABLED)';
  r.smtpServer = {
    host: '$(env:MAIL_HOST)',
    port: '$(env:MAIL_PORT)',
    from: '$(env:MAIL_FROM)',
    fromDisplayName: '$(env:MAIL_FROM_NAME)',
    auth: 'true',
    user: '$(env:MAIL_USERNAME)',
    password: '$(env:MAIL_PASSWORD)',
    starttls: 'true',
  };
  return r;
}

export function buildAll(outDir) {
  mkdirSync(outDir, { recursive: true });
  const dev = JSON.parse(readFileSync(DEV, 'utf8'));
  writeFileSync(join(outDir, 'lifeplatform.json'), JSON.stringify(buildRealm(dev), null, 2) + '\n');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const outDir = process.argv[2];
  if (!outDir) {
    console.error('Usage: node deploy/keycloak/build-realms.mjs <outDir>');
    process.exit(1);
  }
  buildAll(resolve(outDir));
  console.log(`Wrote the production realm to ${outDir}`);
}
