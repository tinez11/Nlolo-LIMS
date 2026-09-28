import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';

/**
 * axe over the console's list screens, compared against a committed baseline.
 *
 * A baseline rather than an assertion of zero, because axe had never run on this console
 * when this spec was written and the true count was unknown. Zero would have been red from
 * the first run and skipped; a baseline means every change can prove it added nothing, and
 * the redesign series can prove it removed something. A rule that appears, or a count that
 * grows, fails. A count that shrinks passes and should be re-recorded (A11Y_RECORD=1).
 *
 * Admin, because ADMIN holds every staff role and so reaches every screen below.
 */
const ROUTES = [
  '/staff/policies',
  '/staff/claims',
  '/staff/underwriting',
  '/staff/clients/individuals',
  '/staff/arrears',
  '/staff/field-receipts',
  '/staff/gl-postings',
  '/staff/chart-of-accounts',
  '/staff/treaties',
  '/staff/regulatory-returns',
  '/staff/bank-transfers',
  '/staff/products',
  '/staff/clients/organisations',
  '/staff/agents',
  '/staff/notifications/messages',
  '/staff/notifications/templates',
  '/staff/audit-log',
];

const BASELINE = 'e2e/a11y-baseline.json';
type Counts = Record<string, number>;

test.use({ storageState: 'e2e/.auth/staff-admin.json' });

test('no accessibility violation beyond the recorded baseline', async ({ page }) => {
  test.slow();
  const found: Counts = {};

  // The tabbed record joins the sweep, found rather than written down: policy numbers are
  // minted per run, so a literal would rot the way three e2e fixtures already have.
  await page.goto('/staff/policies');
  const firstPolicy = page.getByRole('table', { name: 'Policies' }).getByRole('button').first();
  await expect(firstPolicy).toBeVisible({ timeout: 30_000 });
  const policyRecord = `/staff/policies/${(await firstPolicy.textContent())?.trim()}`;

  // And the one-scroll record with a section bar, which is the other structural shape on the
  // platform and so has to be swept too. Reached through the drawer rather than read off the
  // table, because the claims register's first column is the claim TYPE -- there is no id in
  // any cell to build a URL from, and the drawer's own link is the only route a person has.
  await page.goto('/staff/claims');
  await page.getByRole('table', { name: 'Claims' }).getByRole('button').first().click();
  await page.getByRole('link', { name: /full detail/i }).click();
  await expect(page).toHaveURL(/\/staff\/claims\/[0-9a-f-]{36}$/);
  const claimRecord = new URL(page.url()).pathname;

  // The third record shape: two columns, a pinned rail and no tab or section bar. Reached
  // through its drawer for the same reason the claim record is -- the treaties table's first
  // column is a reinsurer's name, so no cell carries an id to build a URL from.
  await page.goto('/staff/treaties');
  await page.getByRole('table', { name: 'Treaties' }).getByRole('button').first().click();
  await page.getByRole('link', { name: /full detail/i }).click();
  await expect(page).toHaveURL(/\/staff\/treaties\/[0-9a-f-]{36}$/);
  const treatyRecord = new URL(page.url()).pathname;

  // The fourth record shape, and the last: two columns with a pinned rail AND tabs. Read off
  // the clients table, where a row's own link carries the party id -- unlike the claim and
  // treaty registers, whose first column is a type and a name, so those two go through a drawer.
  await page.goto('/staff/clients/individuals');
  const firstClient = page.getByRole('table', { name: 'Clients' }).getByRole('link').first();
  await expect(firstClient).toBeVisible({ timeout: 30_000 });
  await firstClient.click();
  await expect(page).toHaveURL(/\/staff\/parties\/[0-9a-f-]{36}$/, { timeout: 30_000 });
  const clientRecord = new URL(page.url()).pathname;

  for (const route of [...ROUTES, policyRecord, claimRecord, treatyRecord, clientRecord]) {
    await page.goto(route);
    await expect(page.locator('h1')).toBeVisible({ timeout: 30_000 });
    // Let the first data fetch land, so axe sees the table rather than a skeleton. Not
    // `networkidle`: the dev server's HMR socket and silent token renewal mean this app
    // never goes idle, and the wait simply times out.
    await expect(page.locator('.animate-pulse, .animate-spin')).toHaveCount(0, { timeout: 30_000 });

    const result = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
      .analyze();
    for (const violation of result.violations) {
      found[`${route} :: ${violation.id}`] = violation.nodes.length;
    }
  }

  if (process.env.A11Y_RECORD) {
    const sorted = Object.fromEntries(Object.entries(found).sort(([a], [b]) => a.localeCompare(b)));
    writeFileSync(BASELINE, `${JSON.stringify(sorted, null, 2)}\n`);
    return;
  }

  const baseline = JSON.parse(readFileSync(BASELINE, 'utf8')) as Counts;
  const regressions = Object.entries(found)
    .filter(([key, count]) => count > (baseline[key] ?? 0))
    .map(([key, count]) => `${key}: ${count} (baseline ${baseline[key] ?? 0})`);
  expect(regressions, 'new or growing axe violations').toEqual([]);
});
