# How e2e tests sign in under Authentik

Type: grilling
Status: resolved
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

The Playwright setups (`frontend/e2e/auth*.setup.ts`, `guards.ts`) sign in through Keycloak's page by its accessible names, and `creditLife.ts` uses the password grant. Under Authentik:

- **The sign-in page:** variant A of [Login look in Authentik](09-login-look.md), username and password on one page, inside Authentik's web components. Do the setups drive it through the UI, with the selectors that pierce shadow DOM that Playwright supports, or get tokens another way?
- **The password grant:** Authentik's `password` grant wants an app-password token, not the user's password. See [Can Authentik issue the tokens universal login needs?](05-tokens-and-universal-login.md). Does `creditLife.ts` switch to an app password per test user, or to a UI sign-in?
- **The staff sign-in code in e2e:** it's off in dev (`STAFF_SIGN_IN_CODE`). Does any e2e test turn it on and read the code from the mock SMS gateway?
- **Dev test users** (with `iam` seed job roles): how are they created in Authentik, by a dev-only blueprint or a seed script?
- **Memory:** the box has 7.8 GB, and e2e runs with `--workers=2`. Authentik's server needs about 1 GiB.

## Answer

The user's decisions, 2026-11-19.

Today's facts:
- Ten `auth*.setup.ts` files fill `#username` and `#password` on Keycloak's page and save `storageState` (Keycloak's session cookie).
- Nine specs use `creditLife.ts`, which mints real tokens with the password grant (`devpassword`).
- The 14 dev test users live in the realm JSON, with job roles from the `iam` seed.
- No e2e covers the staff sign-in code.

1. **UI sign-in:** the setups use `getByLabel('Username')`, `getByLabel('Password')` and `getByRole('button', { name: 'Log in' })` on the variant A page. Playwright pierces the open shadow roots.
   - The ten setups share one sign-in helper.
   - `storageState` captures Authentik's session cookie.
2. **API tokens:** a **dev-only blueprint** gives each dev test user an app-password token with a fixed dev key (e.g. `devpassword-app`). `creditLife.ts` switches its password grant to that key.
3. **Dev test users:** the same dev-only blueprint creates the 14 users with their attributes (`tenant_id`, `party_id`, `home_branch`), workspace groups and `devpassword`.
   - Job roles stay in the `iam` seed.
   - The Keycloak service-account user goes away.
   - The dev blueprint is mounted by the **dev** compose only. [Deploying and operating Authentik](15-deployment-and-operations.md) keeps it out of the server.
4. **One e2e for the staff sign-in code.** The dev blueprint binds the code step for members of an **`e2e-sign-in-code`** group even with `STAFF_SIGN_IN_CODE` off. One test user in that group:
   1. signs in
   2. reads the code from the mock SMS gateway (`:8083/__admin/requests`)
   3. types it
   4. reaches the Dashboard

   Everyone else signs in without a code.
5. **Memory:**
   - The dev compose uses the production `mem_limit`s: server 1 GiB, worker 512 MiB.
   - E2E stays at `--workers=2`.
   - If the first full run shows sign-in timeouts, try `--workers=1` first.
