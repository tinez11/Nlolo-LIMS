import { expect, test } from '@playwright/test';
import { issueRealPolicy } from './policies';
import { expectStaffShellReady } from './guards';

/**
 * Two capabilities that were open to every staff member until now, checked from the side that
 * matters: the person who should NOT have them.
 *
 * Both were `hasRole('REALM_STAFF')`, so an underwriter or a claims assessor could price a life
 * product and write off a premium. Neither is a plausible authorisation on a regulated
 * insurer's ledger, and neither was tested from this direction — the pre-existing 403 tests
 * only proved that an AGENT was refused, which was true of every staff endpoint and therefore
 * said nothing about who inside the staff realm may act.
 *
 * The server is the real gate; these specs check that the console does not offer a route to a
 * guaranteed 403, and that the refusal it does show is legible.
 */

test.describe('product authoring is admin-only', () => {
  // The project default identity: a real staff member with a real job that is not this one.
  test('an underwriter is offered no way to author a product', async ({ page }) => {
    await page.goto('/staff/products');
    await expectStaffShellReady(page);
    await expect(page.getByRole('heading', { name: 'Products' })).toBeVisible({ timeout: 30_000 });

    // The catalogue itself stays readable -- issuing a policy needs it, so a gate that also
    // caught the reads would have broken underwriting for the role that most needs it.
    await expect(page.getByRole('table')).toBeVisible({ timeout: 20_000 });

    await expect(page.getByRole('link', { name: 'New product' })).not.toBeVisible();
  });

  test('and reaching the authoring form directly is refused before any of it is filled in', async ({
    page,
  }) => {
    await page.goto('/staff/products/new');
    await expectStaffShellReady(page);

    await expect(page.getByText('You do not have access to this')).toBeVisible({ timeout: 30_000 });
    // Refused BEFORE the work, not on submit. This page fetches nothing on mount, so without
    // an explicit check a non-admin would fill the whole form and learn at the end.
    await expect(page.getByLabel('Product code')).not.toBeVisible();
  });
});

test.describe('product authoring is offered to an admin', () => {
  test.use({ storageState: 'e2e/.auth/staff-admin.json' });

  test('the admin sees New product, and the form itself', async ({ page }) => {
    await page.goto('/staff/products');
    await expectStaffShellReady(page);
    await expect(page.getByRole('link', { name: 'New product' })).toBeVisible({ timeout: 30_000 });

    await page.getByRole('link', { name: 'New product' }).click();
    await expect(page.getByLabel('Product code')).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('You do not have access to this')).not.toBeVisible();
  });
});

test.describe('waiving a premium is finance-only', () => {
  test('an underwriter is offered no Waive action, but can still request payment', async ({
    page,
  }) => {
    // Its own policy, issued here. The literal POL-6BD5702F was 'the seeded policy' until
    // the volumes were last reset -- policy numbers are minted POL-<random>, so it can never
    // exist again and both tests in this block were failing for a reason unrelated to who
    // may waive a premium.
    test.slow();
    const policyNumber = await issueRealPolicy(page, 'E2E role-gates fixture');
    await page.goto(`/staff/policies/${policyNumber}?tab=billing`);
    await expect(page.getByRole('heading', { name: 'Invoices' })).toBeVisible({ timeout: 30_000 });

    // Request payment stays open to everyone -- asking a customer to pay takes nothing away
    // from anyone, and agents and customers can both do it already. Anchoring on it first is
    // what stops the absence below being satisfied by an unrendered panel.
    await expect(page.getByRole('button', { name: 'Request payment' }).first()).toBeVisible({
      timeout: 20_000,
    });
    await expect(page.getByRole('button', { name: 'Waive', exact: true })).not.toBeVisible();
  });
});

test.describe('waiving a premium is offered to finance', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('a finance officer still has the Waive action', async ({ page }) => {
    // Its own policy, issued here. The literal POL-6BD5702F was 'the seeded policy' until
    // the volumes were last reset -- policy numbers are minted POL-<random>, so it can never
    // exist again and both tests in this block were failing for a reason unrelated to who
    // may waive a premium.
    test.slow();
    const policyNumber = await issueRealPolicy(page, 'E2E role-gates fixture');
    await page.goto(`/staff/policies/${policyNumber}?tab=billing`);
    await expect(page.getByRole('heading', { name: 'Invoices' })).toBeVisible({ timeout: 30_000 });

    await expect(page.getByRole('button', { name: 'Waive', exact: true }).first()).toBeVisible({
      timeout: 20_000,
    });
  });
});
