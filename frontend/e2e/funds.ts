import { expect, type Browser } from '@playwright/test';
import { asAdmin } from './admin';

/**
 * A new fund takes no price until a second person approves it (2026-11-12, finance finding 126). Finance adds the
 * funds in these specs, so the admin is the second person.
 */
export async function approveFundsAsAdmin(browser: Browser, codes: readonly string[]): Promise<void> {
  await asAdmin(browser, async (admin) => {
    await admin.goto('/staff/funds');
    for (const code of codes) {
      const card = admin.getByRole('listitem', { name: `Fund ${code}` });
      await card.getByRole('button', { name: 'Approve fund' }).click();
      await expect(card.getByRole('button', { name: 'Prices' })).toBeVisible({ timeout: 15_000 });
    }
  });
}
