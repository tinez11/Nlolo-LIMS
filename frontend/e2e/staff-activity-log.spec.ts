import { expect, request, test } from '@playwright/test';
import { asAdmin } from './admin';
import { staffToken } from './creditLife';
import { expectNavItemsHidden, expectRouteDenied } from './guards';

const API = 'http://localhost:8080';

/**
 * The activity log (2026-11-14, the user: "well tracked audit log with clear information of tracking seen by admins").
 * The admin makes a change the platform refuses -- so nothing in the shared dev tenant moves -- and finds it in the log
 * under their name, in words, with the reason they were given.
 */
test.describe('staff activity log', () => {
  test('an admin finds a refused change, in words, with its reason', async ({ browser }) => {
    const http = await request.newContext();
    const token = await staffToken(http, 'staff.admin');
    const refused = await http.put(`${API}/organisation/branding`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { primaryColour: 'navy', secondaryColour: '#0E8C7F', typeface: 'SANS', layout: 'CLASSIC', paper: 'A4', hasLogo: false },
    });
    expect(refused.status()).toBe(400);
    await http.dispose();

    await asAdmin(browser, async (page) => {
      await page.goto('/staff/audit-log?outcome=REFUSED&kind=settings');
      await expect(page.getByRole('heading', { name: 'Activity log', level: 1 })).toBeVisible();

      const line = page.getByRole('list', { name: 'Activity' })
        .getByRole('button', { name: /Saved the branding and document layout/ }).first();
      await expect(line).toBeVisible();
      await line.click();
      await expect(page.getByText('Colours are written #RRGGBB, for example #17324D.').first()).toBeVisible();
      await expect(page.getByRole('button', { name: /^Only / })).toBeVisible();
    });
  });

  test.describe('someone without audit.view', () => {
    test.use({ storageState: 'e2e/.auth/staff-finance.json' });

    test('is neither offered nor let into the log', async ({ page }) => {
      await page.goto('/staff');
      await expectNavItemsHidden(page, 'Activity log');
      await expectRouteDenied(page, '/staff/audit-log');
    });
  });
});
