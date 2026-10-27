import { expect, type Browser } from '@playwright/test';
import { approverPage } from './approverSession';

/**
 * A second finance person confirms the mobile-money money a spec asked for (2026-10-27, user decision: with no live
 * integration yet, a request the rail accepts is receipted like every other method -- it pays nothing until finance
 * confirms it). Before this the mock rail's "accepted" paid at once, so every spec that collects a premium or a
 * top-up now passes through here, as the person who did not ask for it.
 *
 * Confirms every mobile-money collection still waiting on the policy, from the payments register.
 */
export async function confirmRequestedPayments(browser: Browser, policyNumber: string): Promise<void> {
  const { page, context } = await approverPage(browser);
  try {
    const register = `/staff/payments?state=waiting&direction=IN&policy=${encodeURIComponent(policyNumber)}`;
    const waiting = page.getByRole('row').filter({ hasText: 'Mobile money' });
    const none = page.getByText('No payments match');
    await page.goto(register);
    await expect(waiting.first()).toBeVisible({ timeout: 30_000 });
    for (;;) {
      // The row's own button, not the row: a register row opens from its first cell.
      await waiting.first().getByRole('button').first().click();
      await expect(page).toHaveURL(/\/staff\/receipts\/[0-9a-f-]{36}/, { timeout: 15_000 });
      await page.getByRole('button', { name: 'Confirm', exact: true }).click({ timeout: 30_000 });
      await page.getByRole('button', { name: 'Confirm receipt' }).click();
      await expect(page.getByRole('heading', { name: 'Confirmed' })).toBeVisible({ timeout: 15_000 });
      await page.goto(register);
      // Loaded, one way or the other, before asking whether another is waiting.
      await expect(waiting.first().or(none)).toBeVisible({ timeout: 30_000 });
      if (await none.isVisible()) break;
    }
  } finally {
    await context.close();
  }
}
