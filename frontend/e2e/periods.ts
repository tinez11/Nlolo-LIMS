import { expect, type Page } from '@playwright/test';

/**
 * A month of its own for a spec that posts into a closing month and then locks it (the IFRS 17 engine and expense
 * allocation specs): the one before the earliest period the ledger knows -- dev's own postings start in 2026.
 *
 * Earlier, not just untouched: the engine's closing figures are balances to date, so a month after an earlier run's would
 * carry that run's postings too -- and a month with an unlocked earlier month holding postings cannot lock. Each spec
 * locks its month at the end: a closing month with postings would stop every later month from locking.
 */
export async function untouchedPeriod(page: Page): Promise<string> {
  await page.goto('/staff/periods');
  const list = page.getByRole('list', { name: 'Accounting periods' });
  await expect(list).toBeVisible({ timeout: 15_000 });
  const known = (await list.getByRole('listitem').evaluateAll((items) => items.map((i) => i.getAttribute('aria-label') ?? '')))
    .map((label) => label.replace('Period ', ''))
    .filter((p) => /^\d{4}-\d{2}$/.test(p))
    .sort();
  const before = known[0] !== undefined && known[0] < '2001-01' ? known[0] : '2001-01';
  const [y, m] = before.split('-').map(Number);
  const period = m === 1 ? `${y - 1}-12` : `${y}-${`${m - 1}`.padStart(2, '0')}`;
  await page.getByLabel('Another period (YYYY-MM)').fill(period);
  await page.getByRole('button', { name: 'Show', exact: true }).click();
  await expect(page.getByRole('listitem', { name: `Period ${period}` }).getByRole('button', { name: 'Start closing' }))
    .toBeVisible();
  return period;
}

/** On the periods page, with the month shown: two clicks each -- the action, then the confirmation that replaces it. */
export async function startClosing(page: Page, period: string): Promise<void> {
  const row = page.getByRole('listitem', { name: `Period ${period}` });
  await row.getByRole('button', { name: 'Start closing' }).click();
  await row.getByRole('button', { name: 'Start closing' }).click();
  await expect(row).toContainText('Closing since', { timeout: 15_000 });
}

export async function lockPeriod(page: Page, period: string): Promise<void> {
  await page.goto('/staff/periods');
  await page.getByLabel('Another period (YYYY-MM)').fill(period);
  await page.getByRole('button', { name: 'Show', exact: true }).click();
  const row = page.getByRole('listitem', { name: `Period ${period}` });
  await row.getByRole('button', { name: 'Lock period' }).click();
  await row.getByRole('button', { name: 'Lock period' }).click();
  await expect(row).toContainText('Locked by', { timeout: 15_000 });
}
