import { expect, test } from '@playwright/test';
import { asAdmin } from './admin';
import { expectNavItemsHidden, expectRouteDenied } from './guards';

/**
 * Organisation settings (2026-11-09, the user: "give the organization more freedom to market themselves in documents,
 * receipts and the exports"; decision O2: administrators only). Nothing is saved here -- the dev tenant's letterhead
 * is shared by every other spec's documents -- so the draft is checked through the live preview.
 */
test.describe('staff organisation settings', () => {
  test('an admin drafts a new look and sees it in the preview, without saving', async ({ browser }) => {
    await asAdmin(browser, async (page) => {
      await page.goto('/staff/organisation');
      await expect(page.getByRole('heading', { name: 'Organisation', level: 1 })).toBeVisible();
      await expect(page.getByRole('tab', { name: /Signatories/ })).toBeVisible();
      await expect(page.getByRole('tab', { name: /Branches/ })).toBeVisible();

      const preview = page.waitForResponse((r) => r.url().endsWith('/organisation/preview') && r.status() === 200);
      await page.getByLabel('Trading name').fill('Bima Bora');
      await page.getByRole('button', { name: /^Banded/ }).click();
      const response = await preview;
      expect(response.headers()['content-type']).toContain('application/pdf');
      await expect(page.getByLabel('Preview of a receipt in your branding')).toBeVisible();

      await page.getByRole('tab', { name: /Branches/ }).click();
      await expect(page.getByRole('table', { name: 'Branches' }).getByText('Head office')).toBeVisible();
    });
  });

  test.describe('someone who is not an administrator', () => {
    test.use({ storageState: 'e2e/.auth/staff-finance.json' });

    test('is neither offered nor let into the settings', async ({ page }) => {
      await page.goto('/staff');
      await expectNavItemsHidden(page, 'Organisation');
      await expectRouteDenied(page, '/staff/organisation');
    });
  });
});
