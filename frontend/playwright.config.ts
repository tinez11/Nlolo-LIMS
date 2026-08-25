import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end against the REAL stack: real Keycloak, real backend, real Postgres.
 *
 * There are no fabricated JWTs anywhere in this suite, and that is a hard rule
 * rather than a preference. This project has twice shipped a completely green test
 * suite over a real credential path that did not work at all -- the M1 database role
 * and the M11 Keycloak claim mappers -- because every test minted its own identity.
 * A brand-new SPA doing PKCE against four realms is the highest-risk possible place
 * to repeat that, and mocked auth would pass whether or not `lifeplatform-spa`
 * exists in the realm at all.
 *
 * Prerequisites (see backend/infra/docker-compose.yml):
 *   docker compose -f ../backend/infra/docker-compose.yml up -d
 *   ../backend/scripts/seed-dev-data.sh
 */
const APP = 'http://localhost:5173';

export default defineConfig({
  testDir: './e2e',
  // Auth is a real redirect chain through an external IdP; give it room.
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list']],

  use: {
    baseURL: APP,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },

  projects: [
    { name: 'setup', testMatch: /.*\.setup\.ts/ },
    {
      name: 'staff',
      testMatch: /staff-.*\.spec\.ts/,
      dependencies: ['setup'],
      use: {
        ...devices['Desktop Chrome'],
        // Captures Keycloak's SSO cookie, not app tokens -- the SPA holds tokens in
        // memory only, so each page load re-authenticates silently against that
        // cookie. Which means this file exercises the real silent-SSO path too.
        storageState: 'e2e/.auth/staff.json',
      },
    },
    {
      name: 'agents',
      testMatch: /agents-.*\.spec\.ts/,
      dependencies: ['setup'],
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'e2e/.auth/agent.json',
      },
    },
  ],

  webServer: {
    command: 'npm run dev',
    url: APP,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
