import { expect, type Browser, type Page } from '@playwright/test';
import { asAdmin } from './admin';
import { dmy } from './dates';
import { fillBasics, publishVersion, step } from './publishForm';
import { selectAgentOfRecord } from './policies';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';

/**
 * Fixtures for "an agent who has actually earned something".
 *
 * These were staff-distribution.spec.ts's own private helpers. They moved here when the commission
 * view specs needed the same chain, because commission is only real at the END of it: an ACTIVE
 * product, a commission plan for that product, and a policy in force naming the agent. Skip the
 * plan and the policy still issues, the test still goes green on its own terms, and the agent earns
 * nothing -- which is exactly how the first run of those specs failed.
 */

const FUTURE_LICENSE_EXPIRY = `${new Date().getFullYear() + 5}-01-01`;

export async function onboardRealAgent(page: Page): Promise<string> {
  await page.goto('/staff/agents/new');
  await page.getByRole('button', { name: 'Search for a VERIFIED party by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill('Amina');
  await page.getByText('Amina Owner').click();
  await page.getByLabel('License number').fill(`E2E-LIC-${Date.now()}`);
  await page.getByLabel('License expiry date').fill(dmy(FUTURE_LICENSE_EXPIRY));
  await page.getByRole('button', { name: 'Onboard agent' }).click();
  await expect(page).toHaveURL(/\/staff\/agents\/[0-9a-f-]{36}$/, { timeout: 15_000 });
  return page.url().split('/').pop() as string;
}

/** A fresh ACTIVE product, so plan authoring never touches the shared seeded
 *  "Demo Term Life" fixture other suites also read. Returns the exact label
 *  text the product picker renders (`${productName} (${productCode})`). */
export async function createRealActiveProduct(page: Page): Promise<{ productId: string; optionLabel: string }> {
  const code = `E2E-DIST-${Date.now()}`;
  const name = `E2E Distribution Test ${code}`;

  await page.goto('/staff/products/new');
  await page.getByLabel('Product code').fill(code);
  await page.getByLabel('Product name').fill(name);
  await page.getByLabel('Default currency').fill('TZS');
  await page.getByRole('button', { name: 'Create product' }).click();
  await expect(page.getByText('DRAFT')).toBeVisible();

  // The rating rows the server requires come filled in as neutral (every age, every amount, x1).
  // The TIRA filing that authorises this version -- required as of V12.
  await fillBasics(page, 'TIRA/E2E/0001');
  // Required on an individual product as of product step 2 (PayoutPlanValidator). Without it the
  // form refuses the publish, the product stays a DRAFT, and every caller below fails far from
  // here -- on a Product picker that simply does not list it.
  await step(page, 'What it pays');
  await page.getByLabel('Free-look days').fill('15');
  await publishVersion(page);
  // PROOF it published. This used to wait for the product page's url, which the CREATE step had
  // already reached -- so a publish the form refused passed straight through, leaving a DRAFT that
  // no picker lists. The server's own answer to the publish is what is asserted now; armed before
  // the click, so a fast response cannot be missed.
  const published = page.waitForResponse(
    (r) => r.request().method() === 'POST' && /\/products\/[0-9a-f-]{36}\/versions$/.test(r.url()),
    { timeout: 15_000 },
  );
  // Publishing retires the currently-active version, so it is confirmed.
  await page.getByRole('button', { name: 'Publish and make active' }).click();
  expect((await published).status(), 'the version must actually publish').toBe(201);

  const productId = page.url().split('/').pop() as string;
  return { productId, optionLabel: `${name} (${code})` };
}

/** Fills and submits the default (first, FIRST_YEAR/rate) row of the
 *  create-commission-plan form -- no need to touch `useFieldArray` at all for
 *  a minimal one-rule plan. Assumes the product is already selected and the
 *  "No plan applies yet" 404 state (with the inline create form) is showing. */
export async function createDefaultPlan(page: Page, rate: string): Promise<void> {
  const form = page.locator('form').filter({ hasText: 'Create a plan for this product' });
  await form.getByPlaceholder('0.1000 (10%)').fill(rate);
  await form.getByRole('button', { name: 'Create plan' }).click();
}

export async function issueRealPolicyForAgent(
  page: Page,
  productOptionLabel: string,
  agentId: string,
  reason = 'E2E distribution fixture',
): Promise<string> {
  // Manual issue names a real, unissued case now. The product is still chosen by hand after
  // the prefill: these tests issue against a product they published themselves, not the
  // seeded one the case was opened against.
  const caseId = await caseAwaitingManualIssue(page);
  await page.goto('/staff/policies/new');
  await selectUnderwritingCase(page, caseId);
  await page.getByLabel('Product').selectOption({ label: productOptionLabel });
  await expect(page.getByText('Resolving product version…')).not.toBeVisible();
  await page.getByLabel('Sum assured').fill('2000000.00');
  await page.getByLabel('Premium', { exact: true }).fill('800.00');
  await selectAgentOfRecord(page, agentId);
  // Required, and MIGRATION so the policy is in force rather than an offer.
  await page.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
  await page.getByLabel('Reason for manual issue').fill(reason);
  await page.getByRole('button', { name: 'Issue policy' }).click();
  await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 60_000 });
  return page.url().split('/').pop() as string;
}

/**
 * The whole chain, for a spec that needs an agent with real commission this month: a fresh ACTIVE
 * product, a FIRST_YEAR plan on it, and a policy in force naming `agentId` as agent of record.
 *
 * Runs as admin for the product and as finance for the plan and the issuance -- the two real
 * identities those actions are gated on. Returns the policy number the accrual is booked against.
 */
export async function giveAgentEarningPolicy(
  browser: Browser,
  agentId: string,
  reason: string,
): Promise<string> {
  const { optionLabel } = await asAdmin(browser, createRealActiveProduct);

  const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
  const financePage = await financeContext.newPage();
  try {
    // The plan is authored from the agent's own page, which is where the create form lives.
    await financePage.goto(`/staff/agents/${agentId}`);
    await financePage.getByLabel('Product').selectOption({ label: optionLabel });
    await expect(financePage.getByText('No plan applies to this agent for this product yet.')).toBeVisible();
    await createDefaultPlan(financePage, '0.1000');
    await expect(financePage.getByText('Active plan', { exact: true })).toBeVisible({ timeout: 15_000 });

    return await issueRealPolicyForAgent(financePage, optionLabel, agentId, reason);
  } finally {
    await financeContext.close();
  }
}
