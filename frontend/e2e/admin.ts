import type { Browser, Page } from '@playwright/test';

/**
 * Runs `work` as `staff.admin`, in its own browser context, and closes it afterwards.
 *
 * Product authoring is `hasRole('REALM_STAFF') and hasRole('ADMIN')` — it used to be
 * plain `REALM_STAFF`, which was the defect: an underwriter could price a life product.
 * The consequence for e2e is that any spec needing a *fresh product* as a fixture must
 * mint it as admin, even when the behaviour under test belongs to someone else.
 *
 * Scoping that to a nested context, rather than running the whole spec as admin, is the
 * point. `staff.admin` carries every staff role, so a test that runs entirely as admin
 * proves nothing about who may do the thing — and three of these specs exist precisely
 * to prove what a finance officer or an underwriter can do. The fixture is admin's; the
 * assertions stay the original identity's.
 */
export async function asAdmin<T>(browser: Browser, work: (page: Page) => Promise<T>): Promise<T> {
  const context = await browser.newContext({ storageState: 'e2e/.auth/staff-admin.json' });
  try {
    return await work(await context.newPage());
  } finally {
    await context.close();
  }
}
