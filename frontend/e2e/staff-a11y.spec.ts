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
  '/staff/products',
];

const BASELINE = 'e2e/a11y-baseline.json';
type Counts = Record<string, number>;

test.use({ storageState: 'e2e/.auth/staff-admin.json' });

test('no accessibility violation beyond the recorded baseline', async ({ page }) => {
  test.slow();
  const found: Counts = {};

  for (const route of ROUTES) {
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
