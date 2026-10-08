import { expect, type Page } from '@playwright/test';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';

/**
 * Issues an in-force policy by hand (MIGRATION basis) against a product chosen by its label, and lands on its
 * record. Shared by every spec that needs a policy of one particular kind -- the policy list shows no product,
 * so "open the first policy" could be any kind at all.
 */
export async function issuePolicyAgainst(page: Page, productLabel: string, reason: string,
                                         options: { termMonths?: number } = {}): Promise<string> {
  // Manual issue names a real, unissued case. This one still selects the product by hand after the prefill,
  // because callers issue against a product of their choosing rather than the case's seeded one.
  const caseId = await caseAwaitingManualIssue(page);
  await page.goto('/staff/policies/new');
  await selectUnderwritingCase(page, caseId);
  await page.getByLabel('Product').selectOption({ label: productLabel });
  await expect(page.getByText('Resolving product version…')).not.toBeVisible();
  await page.getByLabel('Sum assured').fill('2000000.00');
  await page.getByLabel('Premium', { exact: true }).fill('800.00');
  // A savings product matures, so it carries a term.
  if (options.termMonths) await page.getByLabel('Policy term (months)').fill(String(options.termMonths));
  // Required, and MIGRATION so the policy is in force rather than an offer.
  await page.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
  await page.getByLabel('Reason for manual issue').fill(reason);
  await page.getByRole('button', { name: 'Issue policy' }).click();
  await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
  return page.url().split('/').pop() as string;
}
