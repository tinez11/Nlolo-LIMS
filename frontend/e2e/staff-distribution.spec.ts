import { expect, type Page, test } from '@playwright/test';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';
import { selectAgentOfRecord } from './policies';
import { dmy } from './dates';
import { asAdmin } from './admin';

/**
 * Distribution (agents/commissions) e2e coverage against the real backend.
 *
 * Onboarding an agent, authoring a commission plan, and requesting a payout
 * are all gated on FINANCE_OFFICER/ADMIN -- `staff.underwriter`, this
 * project's default identity, carries neither, so most of this file runs
 * under a separate real login (auth-finance.setup.ts), the same
 * multi-identity shape staff-claims-adjudication.spec.ts already established.
 *
 * Unlike underwriting, an agent IS re-discoverable after the fact: a policy's
 * own `agentOfRecordId` genuinely round-trips through `GET /policies`
 * (verified directly against the real wire response before building this --
 * see api/distribution.ts's own comment), so one test below proves the
 * drill-in link from a real policy, not just the onboarding redirect.
 *
 * There is no manual "close a commission statement" endpoint anywhere on this
 * platform -- only a monthly `pg_cron` sweep moves OPEN -> CLOSED -- so a
 * freshly-accrued statement in this dev environment is never payable within a
 * test's lifetime. The payout test below exploits that honestly: it proves
 * the real 409 `CommissionStatement.markPayoutRequested` throws for an OPEN
 * statement, rather than pretending to reach PAID.
 */

const FUTURE_LICENSE_EXPIRY = `${new Date().getFullYear() + 5}-01-01`;

async function onboardRealAgent(page: Page): Promise<string> {
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
async function createRealActiveProduct(page: Page): Promise<{ productId: string; optionLabel: string }> {
  const code = `E2E-DIST-${Date.now()}`;
  const name = `E2E Distribution Test ${code}`;

  await page.goto('/staff/products/new');
  await page.getByLabel('Product code').fill(code);
  await page.getByLabel('Product name').fill(name);
  await page.getByLabel('Default currency').fill('TZS');
  await page.getByRole('button', { name: 'Create product' }).click();
  await expect(page.getByText('DRAFT')).toBeVisible();

  const ratingSection = page.locator('p', { hasText: 'Rating table -- must cover' }).locator('..');
  await ratingSection.getByRole('button', { name: 'Remove rating factor' }).last().click();
  await ratingSection.getByLabel('Rating factor 1 band').fill('18-30');
  // AGE is rated by range now: the band text is a label, these two are what the platform
  // resolves against. Publishing without them is a real 422.
  await ratingSection.getByLabel('Rating factor 1 from age').fill('18');
  await ratingSection.getByLabel('Rating factor 1 to age').fill('30');
  await ratingSection.getByRole('button', { name: 'Add rating factor' }).click();
  await ratingSection.locator('select').nth(1).selectOption('SUM_ASSURED_BAND');
  await ratingSection.getByLabel('Rating factor 2 band').fill('1-99999999');
  await page.getByLabel('Effective date').fill(dmy('2026-01-01'));
  // The TIRA filing that authorises this version -- required as of V12.
  await page.getByLabel('TIRA filing reference').fill('TIRA/E2E/0001');
  await page.getByLabel('TIRA approval date').fill(dmy('2026-01-15'));
  await page.getByRole('button', { name: 'Publish version' }).click();
  // Publishing retires the currently-active version, so it is confirmed.
  await page.getByRole('button', { name: 'Publish and make active' }).click();
  await expect(page).toHaveURL(/\/staff\/products\/[0-9a-f-]{36}$/, { timeout: 15_000 });

  const productId = page.url().split('/').pop() as string;
  return { productId, optionLabel: `${name} (${code})` };
}

/** Fills and submits the default (first, FIRST_YEAR/rate) row of the
 *  create-commission-plan form -- no need to touch `useFieldArray` at all for
 *  a minimal one-rule plan. Assumes the product is already selected and the
 *  "No plan applies yet" 404 state (with the inline create form) is showing. */
async function createDefaultPlan(page: Page, rate: string): Promise<void> {
  const form = page.locator('form').filter({ hasText: 'Create a plan for this product' });
  await form.getByPlaceholder('0.1000 (10%)').fill(rate);
  await form.getByRole('button', { name: 'Create plan' }).click();
}

async function issueRealPolicyForAgent(
  page: Page,
  productOptionLabel: string,
  agentId: string,
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
  await page.getByLabel('Reason for manual issue').fill('E2E distribution fixture');
  await page.getByRole('button', { name: 'Issue policy' }).click();
  await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
  return page.url().split('/').pop() as string;
}

test.describe('staff distribution', () => {
  test('onboards an agent, and a policy naming it as agent of record drills in to the same page', async ({
    page,
    browser,
  }) => {
    // Defensively, for the same reason as the payout test below: this one went from roughly
    // 30s to 38.4s when manual issue started requiring a real underwriting case, and it has
    // already failed once under load at that margin. It is close enough to the budget that
    // the next slow afternoon would fail it for no reason worth reading.
    test.slow();
    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const financePage = await financeContext.newPage();
    const agentId = await onboardRealAgent(financePage);
    await expect(financePage.getByRole('heading', { name: /E2E-LIC-/ })).toBeVisible();
    await financeContext.close();

    // Issuing a policy is REALM_STAFF-broad, not finance-gated -- the default
    // staff.underwriter identity is enough here.
    // Manual issue names a real, unissued case now. The policyholder and product
    // come from it by prefill, so this no longer picks them by hand. The sum assured
    // still does: the case view @JsonIgnores it, so the console cannot read it.
    const caseId = await caseAwaitingManualIssue(page);
    await page.goto('/staff/policies/new');
    await selectUnderwritingCase(page, caseId);
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('800.00');
    await selectAgentOfRecord(page, agentId);
    // Required, and MIGRATION so the policy is in force rather than an offer.
    await page.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
    await page.getByLabel('Reason for manual issue').fill('E2E drill-in fixture');
    await page.getByRole('button', { name: 'Issue policy' }).click();
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });

    /*
     * The link is named by the AGENT, not by its uuid.
     *
     * This assertion used to read `getByRole('link', { name: agentId })`, which passed
     * because the field printed the raw `agentOfRecordId` -- the defect, asserted as the
     * contract. `AgentName` resolves it in two hops (agent -> partyId -> party), since an
     * agent has no name of its own in the distribution module; this agent was onboarded
     * against Amina Owner above, so that is the name that must appear.
     *
     * The click still pins the identity: only the right agent's page is at that url.
     */
    // Scoped to the field, because this policy's policyholder IS the party the agent was
    // onboarded against -- so once both resolve to a name, "Amina Owner" is two links on
    // the page and only the `dt` tells them apart.
    const agentField = page
      .locator('dt')
      .filter({ hasText: 'Agent of record' })
      .locator('xpath=following-sibling::dd[1]');
    const agentLink = agentField.getByRole('link');
    await expect(agentLink).toContainText('Amina Owner');
    await expect(page.getByRole('link', { name: agentId })).toHaveCount(0);
    await agentLink.click();
    await expect(page).toHaveURL(new RegExp(`/staff/agents/${agentId}$`));
  });

  test('a staff.underwriter session can read an agent but sees no management actions', async ({
    page,
    browser,
  }) => {
    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const financePage = await financeContext.newPage();
    const agentId = await onboardRealAgent(financePage);
    // A FRESH product, not the seeded Demo Term Life. A commission plan hangs off a
    // PRODUCT, so once anything -- another suite, or a person clicking around the dev
    // console -- authors a plan on the shared fixture, every agent selecting it shows
    // "Active plan" instead. That is what broke this test: it asserted the empty state
    // on a product that had since acquired a plan.
    //
    // The no-plan state is not incidental here, it is load-bearing: the inline
    // create-plan form ONLY renders in that state, so "canManage is false, no create
    // form" proves nothing on a product that already has a plan. Reading the seeded
    // fixture made the two assertions below quietly vacuous as well as red.
    const { optionLabel } = await asAdmin(browser, createRealActiveProduct);
    await financeContext.close();

    // getAgent/listStatements are REALM_STAFF-broad reads -- staff.underwriter
    // (no FINANCE_OFFICER/ADMIN) can see this page, just not act on it.
    await page.goto(`/staff/agents/${agentId}`);
    await expect(page.getByRole('heading', { name: /E2E-LIC-/ })).toBeVisible();

    await page.getByLabel('Product').selectOption({ label: optionLabel });
    await expect(page.getByText('No plan applies to this agent for this product yet.')).toBeVisible();
    // canManage is false for this identity -- no inline create-plan form.
    await expect(page.getByText('Create a plan for this product')).not.toBeVisible();
    await expect(page.getByRole('button', { name: 'Request payout' })).not.toBeVisible();
  });

  test('creates a commission plan for a fresh product, and it becomes readable afterward', async ({
    browser,
  }) => {
    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const financePage = await financeContext.newPage();

    const agentId = await onboardRealAgent(financePage);
    const { optionLabel } = await asAdmin(browser, createRealActiveProduct);

    await financePage.goto(`/staff/agents/${agentId}`);
    await financePage.getByLabel('Product').selectOption({ label: optionLabel });
    await expect(
      financePage.getByText('No plan applies to this agent for this product yet.'),
    ).toBeVisible();

    await createDefaultPlan(financePage, '0.1000');

    // A real POST -> 201 -> the store writes the response straight into the
    // same (agent, product) slot the 404 came from -- no reload needed to see
    // it flip from "no plan" to "Active plan".
    //
    // `exact` is load-bearing, not tidiness. getByText matches a case-insensitive
    // SUBSTRING by default, and the same panel renders the note "Null means the
    // product's active plan applies instead" -- so the bare locator matched two
    // elements and failed strict mode, but only in the window where both were on
    // screen. It passed in isolation and failed in a full run.
    await expect(financePage.getByText('Active plan', { exact: true })).toBeVisible({ timeout: 15_000 });
    await expect(financePage.getByText('first year')).toBeVisible();

    // Reload and reselect from scratch -- proves this is a real Postgres row,
    // not the store's in-memory state surviving a soft navigation.
    await financePage.reload();
    await financePage.getByLabel('Product').selectOption({ label: optionLabel });
    await expect(financePage.getByText('Active plan', { exact: true })).toBeVisible({ timeout: 15_000 });

    await financeContext.close();
  });

  test('accrues a real commission statement from a real policy, and requesting payout on it genuinely 409s while OPEN', async ({
    browser,
  }) => {
    // Ran at 51.4s against a 60s budget before manual issue required a real underwriting
    // case. Opening, assessing and deciding one adds three more round trips against the real
    // backend, which pushed this over. The work is genuine and the assertions are unchanged;
    // only the budget was wrong. Same reason agents-my-book carries this.
    test.slow();
    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const financePage = await financeContext.newPage();

    const agentId = await onboardRealAgent(financePage);
    const { optionLabel } = await asAdmin(browser, createRealActiveProduct);

    await financePage.goto(`/staff/agents/${agentId}`);
    await financePage.getByLabel('Product').selectOption({ label: optionLabel });
    await expect(
      financePage.getByText('No plan applies to this agent for this product yet.'),
    ).toBeVisible();
    await createDefaultPlan(financePage, '0.1000');
    await expect(financePage.getByText('Active plan', { exact: true })).toBeVisible({ timeout: 15_000 });

    await issueRealPolicyForAgent(financePage, optionLabel, agentId);

    // Accrual is event-driven off policy.PolicyIssued, published inside the
    // same transaction the manual-issue POST commits -- normally visible
    // immediately, but polled defensively rather than assumed synchronous.
    await financePage.goto(`/staff/agents/${agentId}`);
    await expect(async () => {
      await financePage.reload();
      await expect(financePage.getByText('No commission statements yet.')).not.toBeVisible();
    }).toPass({ timeout: 20_000 });

    await financePage.getByRole('button', { name: 'View accruals' }).first().click();
    await expect(financePage.getByText(/first year/)).toBeVisible();

    // The statement is OPEN (only a monthly pg_cron sweep ever closes one) --
    // a genuine 409 from CommissionStatement.markPayoutRequested, not a
    // fabricated rejection.
    await financePage.getByPlaceholder('Payee mobile-money reference').first().fill('255700000000');
    await financePage.getByRole('button', { name: 'Request payout' }).first().click();

    // Money leaving now takes a second, deliberate click, and the confirmation
    // names the amount and payee rather than saying "are you sure".
    await expect(financePage.getByText(/cannot be recalled/)).toBeVisible();
    await financePage.getByRole('button', { name: 'Pay out' }).first().click();
    await expect(financePage.getByRole('alert')).toBeVisible({ timeout: 15_000 });

    await financeContext.close();
  });
});
