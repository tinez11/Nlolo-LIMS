import { expect, test } from '@playwright/test';
import { expectNavItemsHidden, expectRouteDenied } from './guards';

/**
 * `POST /regulatory-returns` (generate) + `GET` (list/get) -- fully built and
 * staff-reachable (FINANCE_OFFICER/ADMIN) since M10, but with zero staff UI
 * until this staff-portal CRUD audit found the gap.
 *
 * `QUARTERLY_PRUDENTIAL` is the one return type seeded in this tenant
 * (regreporting/V2); its `periodKind` is QUARTERLY, so a real period must be
 * `YYYY-Qn` -- a plain `YYYY` period is the real 422 fixture below, not a
 * client-side rule this UI invents.
 */
test.describe('staff regulatory returns', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('generates a real return, previews it, then views full detail', async ({ page }) => {
    // Generation is idempotent per (returnType, period) -- regenerating
    // replaces the prior lines rather than erroring, so a fixed period is
    // fine here even across repeated runs of this spec.
    const period = '2026-Q1';

    await page.goto('/staff/regulatory-returns');
    await expect(page.getByRole('heading', { name: 'Regulatory returns' })).toBeVisible();

    await page.getByRole('button', { name: 'Generate return' }).click();
    await page.getByLabel('Return type').fill('QUARTERLY_PRUDENTIAL');
    await page.getByLabel('Period').fill(period);
    await page.getByRole('button', { name: 'Generate', exact: true }).click();

    // Generation succeeding opens the drawer preview automatically. Radix
    // derives the dialog's accessible name from Dialog.Title (the return
    // type), which overrides the aria-label on SheetContent -- same quirk
    // staff-policies.spec.ts's own comment documents for its drawer.
    const drawer = page.getByRole('dialog', { name: 'QUARTERLY_PRUDENTIAL' });
    await expect(drawer).toBeVisible({ timeout: 15_000 });
    await expect(drawer.getByText('PL-01')).toBeVisible();

    await drawer.getByRole('link', { name: /full detail/i }).click();
    await expect(page).toHaveURL(/\/staff\/regulatory-returns\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    await expect(page.getByRole('heading', { name: 'QUARTERLY_PRUDENTIAL' })).toBeVisible();
    await expect(page.getByText('Policies in force at period end')).toBeVisible();

    // The list shows it too, without a manual reload -- generateReturn refreshes loadList().
    await page.goto('/staff/regulatory-returns');
    await expect(page.getByText(period)).toBeVisible();
  });

  test('rejects a period that does not match the return type\'s periodKind with a real 422', async ({
    page,
  }) => {
    await page.goto('/staff/regulatory-returns');
    await page.getByRole('button', { name: 'Generate return' }).click();
    await page.getByLabel('Return type').fill('QUARTERLY_PRUDENTIAL');
    // A plain annual-shaped period -- QUARTERLY_PRUDENTIAL's periodKind is QUARTERLY.
    await page.getByLabel('Period').fill('2027');
    await page.getByRole('button', { name: 'Generate', exact: true }).click();

    await expect(page.getByRole('alert')).toBeVisible({ timeout: 15_000 });
    // Still on the form, not redirected as if it had succeeded.
    await expect(page.getByLabel('Period')).toBeVisible();
  });

  test('rejects an unseeded return type with a real 422', async ({ page }) => {
    await page.goto('/staff/regulatory-returns');
    await page.getByRole('button', { name: 'Generate return' }).click();
    await page.getByLabel('Return type').fill('NO_SUCH_RETURN_TYPE');
    await page.getByLabel('Period').fill('2026-Q1');
    await page.getByRole('button', { name: 'Generate', exact: true }).click();

    await expect(page.getByRole('alert')).toBeVisible({ timeout: 15_000 });
  });
});

// A separate, non-`test.use`-overridden describe block: staff.underwriter
// carries neither FINANCE_OFFICER nor ADMIN, the same role-gating precedent
// GL postings/chart of accounts/treaties already established.
test.describe('staff regulatory returns -- role gating', () => {
  test('a staff.underwriter session cannot see or reach regulatory returns', async ({ page }) => {
    await page.goto('/staff/policies');
    await expectNavItemsHidden(page, 'Regulatory returns');
    await expectRouteDenied(page, '/staff/regulatory-returns');
  });
});
