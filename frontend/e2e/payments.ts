import { expect, type Browser, type Page } from '@playwright/test';
import { approverPage } from './approverSession';

/**
 * A second finance person confirms the mobile-money money a spec asked for (2026-10-27, user decision: with no live
 * integration yet, a request the rail accepts is receipted like every other method -- it pays nothing until finance
 * confirms it). Before this the mock rail's "accepted" paid at once, so every spec that collects a premium or a
 * top-up now passes through here, as the person who did not ask for it.
 *
 * Confirms every mobile-money collection still waiting on the policy, from Deposits.
 */
export async function confirmRequestedPayments(browser: Browser, policyNumber: string): Promise<void> {
  const { page, context } = await approverPage(browser);
  try {
    const register = `/staff/payments?state=waiting&policy=${encodeURIComponent(policyNumber)}`;
    const waiting = page.getByRole('row').filter({ hasText: 'Mobile money' });
    const none = page.getByText('No deposits match');
    await page.goto(register);
    await expect(waiting.first()).toBeVisible({ timeout: 30_000 });
    for (;;) {
      // The row's own button, not the row: a register row opens from its first cell.
      await waiting.first().getByRole('button').first().click();
      await expect(page).toHaveURL(/\/staff\/receipts\/[0-9a-f-]{36}/, { timeout: 15_000 });
      await page.getByRole('button', { name: 'Confirm', exact: true }).click({ timeout: 30_000 });
      await page.getByRole('button', { name: 'Confirm deposit' }).click();
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

/**
 * Ask the payer's phone for the policy's oldest premium owed, the way staff do it (2026-11-07): the one Deposit page,
 * Mobile money. Called from the policy's page; returns to that page once the request has gone, so a spec reads on as
 * before. The request answers only after the whole AFTER_COMMIT chain -- payment and the rail's acceptance -- has run
 * on its thread, hence 60s.
 */
export async function askPhoneForPremium(page: Page, phone: string): Promise<void> {
  const from = page.url();
  const policyNumber = new URL(from).pathname.split('/').pop()!;
  await page.goto(`/staff/receipts/new?policy=${encodeURIComponent(policyNumber)}&from=policy`);
  await page.getByLabel('How is it being paid?').selectOption('MOBILE_MONEY');
  await expect(page.getByText(/still owes\.$/)).toBeVisible({ timeout: 20_000 });
  await page.getByLabel('Phone number').fill(phone);
  await page.getByRole('button', { name: 'Send request' }).click();
  await expect(page.getByText('Mobile-money request sent')).toBeVisible({ timeout: 60_000 });
  await page.goto(from);
}
