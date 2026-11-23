import { AUTHENTIK } from './signIn';

/**
 * Runs once, before any spec (2026-11-23): can the suite mint API tokens at all?
 *
 * Specs build their fixtures over the API with `apiToken`, which uses Authentik's password grant. Only the dev blueprint
 * allows that grant on the console's client; the production blueprint, mounted on the same dev Authentik, lists the
 * code flow and refresh only. Whichever was applied last wins, so applying the production blueprint after the dev one
 * silently removes the grant -- and then every spec that builds a fixture fails with "invalid_grant", which reads like
 * two hundred separate bugs. Found that way on 2026-11-23. This says it once, with the fix.
 */
export default async function globalSetup(): Promise<void> {
  let response: Response;
  try {
    response = await fetch(`${AUTHENTIK}/application/o/token/`, {
      method: 'POST',
      body: new URLSearchParams({
        grant_type: 'password',
        client_id: 'lifeplatform-spa',
        username: 'staff.admin',
        password: 'dev-app-password-staff.admin',
        scope: 'openid profile lifeplatform',
      }),
    });
  } catch (cause) {
    throw new Error(`Authentik is not answering at ${AUTHENTIK}. Start the dev stack first (backend/infra).`, { cause });
  }
  if (response.ok) return;

  const body = await response.text();
  throw new Error(
    body.includes('invalid_grant')
      ? 'Authentik refused the dev password grant, so no spec could build its fixtures. The console\'s client has lost ' +
          'the grant: the production blueprint was applied after the dev one. Re-apply the dev blueprint (from ' +
          'PowerShell; Git Bash rewrites the path):\n\n' +
          '  docker exec infra-authentik-worker-1 ak apply_blueprint /blueprints/lifeplatform-dev/dev-users.yaml\n\n' +
          'See backend/docs/10-frontend-development.md, Gotcha 1.'
      : `Authentik refused a token for staff.admin: ${response.status} ${body}`,
  );
}
