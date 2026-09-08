import { expect, type Page } from '@playwright/test';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';

/**
 * Issue a real policy, for specs that need one to act on rather than to test.
 *
 * ## Why this exists
 *
 * Five specs pinned themselves to the literal `POL-6BD5702F`, described as "the shared
 * seeded policy". It was, once. Policy numbers are minted `POL-<random>` per issuance, so the
 * moment the volumes were reset that number stopped existing and could never be recreated —
 * it returns zero rows in this database. Thirteen tests across those five files were failing
 * for a reason with nothing to do with what any of them assert, and no amount of re-running
 * would ever have fixed it.
 *
 * The same trap as the hard-coded `AGENT_SENIOR_ID` in agents-my-book and the hard-coded
 * party id in staff-issue-policy and staff-party-kyc: a randomly-minted seed value written
 * down as though it were stable. This is the version of the fix that scales — a spec that
 * needs a policy makes one, and owns it.
 *
 * ## What the caller gets
 *
 * A freshly issued, ACTIVE policy for the seeded Amina Owner, with invoices raised behind it
 * (billing consumes `policy.PolicyIssued`) and a commission accrual if an agent is named.
 * Nobody else's tests touch it, so a spec can mutate it freely — which the beneficiaries
 * spec in particular needs, since it clears and rewrites the designation.
 *
 * @param reason recorded on the policy as `reasonForManualIssue`. Say which spec wanted it;
 *     these rows persist and somebody will eventually ask where they came from.
 */
export async function issueRealPolicy(page: Page, reason: string): Promise<string> {
  // Manual issue names a real, unissued case. The policyholder and product come from it by
  // prefill; the sum assured still has to be typed, because UnderwritingCaseView @JsonIgnores
  // it and the console genuinely cannot read it.
  const caseId = await caseAwaitingManualIssue(page);
  await page.goto('/staff/policies/new');
  await selectUnderwritingCase(page, caseId);
  await expect(page.getByText('Resolving product version…')).not.toBeVisible();
  await page.getByLabel('Sum assured').fill('2000000.00');
  await page.getByLabel('Premium', { exact: true }).fill('800.00');
  await page.getByLabel('Reason for manual issue').fill(reason);
  await page.getByRole('button', { name: 'Issue policy' }).click();
  await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
  return page.url().split('/').pop() as string;
}
