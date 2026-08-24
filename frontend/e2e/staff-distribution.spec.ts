import { expect, type Page, test } from '@playwright/test';

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

const REAL_PARTY_ID = 'd9937444-3873-4336-9cb7-addb486f3e1b';
const FUTURE_LICENSE_EXPIRY = `${new Date().getFullYear() + 5}-01-01`;

async function onboardRealAgent(page: Page): Promise<string> {
  await page.goto('/staff/agents/new');
  await page.getByLabel('Party id').fill(REAL_PARTY_ID);
  await page.getByLabel('License number').fill(`E2E-LIC-${Date.now()}`);
  await page.getByLabel('License expiry date').fill(FUTURE_LICENSE_EXPIRY);
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
  await ratingSection.locator('input[placeholder="Band, e.g. 18-30"]').fill('18-30');
  await ratingSection.getByRole('button', { name: 'Add rating factor' }).click();
  await ratingSection.locator('select').nth(1).selectOption('SUM_ASSURED_BAND');
  await ratingSection.locator('input[placeholder="Band, e.g. 18-30"]').nth(1).fill('1-99999999');
  await page.getByLabel('Effective date').fill('2026-01-01');
  await page.getByRole('button', { name: 'Publish version' }).click();
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
  await page.goto('/staff/policies/new');
  await page.getByLabel('Policyholder party id').fill(REAL_PARTY_ID);
  await page.getByLabel('Product').selectOption({ label: productOptionLabel });
  await expect(page.getByText('Resolving product version…')).not.toBeVisible();
  await page.getByLabel('Sum assured').fill('2000000.00');
  await page.getByLabel('Premium', { exact: true }).fill('800.00');
  await page.getByLabel('Agent of record id (optional)').fill(agentId);
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
    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const financePage = await financeContext.newPage();
    const agentId = await onboardRealAgent(financePage);
    await expect(financePage.getByRole('heading', { name: /E2E-LIC-/ })).toBeVisible();
    await financeContext.close();

    // Issuing a policy is REALM_STAFF-broad, not finance-gated -- the default
    // staff.underwriter identity is enough here.
    await page.goto('/staff/policies/new');
    await page.getByLabel('Policyholder party id').fill(REAL_PARTY_ID);
    await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('800.00');
    await page.getByLabel('Agent of record id (optional)').fill(agentId);
    await page.getByLabel('Reason for manual issue').fill('E2E drill-in fixture');
    await page.getByRole('button', { name: 'Issue policy' }).click();
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });

    const agentLink = page.getByRole('link', { name: agentId });
    await expect(agentLink).toBeVisible();
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
    await financeContext.close();

    // getAgent/listStatements are REALM_STAFF-broad reads -- staff.underwriter
    // (no FINANCE_OFFICER/ADMIN) can see this page, just not act on it.
    await page.goto(`/staff/agents/${agentId}`);
    await expect(page.getByRole('heading', { name: /E2E-LIC-/ })).toBeVisible();

    await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
    await expect(page.getByText('No plan applies to this agent for this product yet.')).toBeVisible();
    // canManage is false for this identity -- no inline create-plan form.
    await expect(page.getByText('Create a plan for this product')).not.toBeVisible();
    await expect(page.getByRole('button', { name: 'Request payout' })).not.toBeVisible();
  });

  test('creates a commission plan for a fresh product, and it becomes readable afterward', async ({
    page,
    browser,
  }) => {
    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const financePage = await financeContext.newPage();

    const agentId = await onboardRealAgent(financePage);
    const { optionLabel } = await createRealActiveProduct(financePage);

    await financePage.goto(`/staff/agents/${agentId}`);
    await financePage.getByLabel('Product').selectOption({ label: optionLabel });
    await expect(
      financePage.getByText('No plan applies to this agent for this product yet.'),
    ).toBeVisible();

    await createDefaultPlan(financePage, '0.1000');

    // A real POST -> 201 -> the store writes the response straight into the
    // same (agent, product) slot the 404 came from -- no reload needed to see
    // it flip from "no plan" to "Active plan".
    await expect(financePage.getByText('Active plan')).toBeVisible({ timeout: 15_000 });
    await expect(financePage.getByText('first year')).toBeVisible();

    // Reload and reselect from scratch -- proves this is a real Postgres row,
    // not the store's in-memory state surviving a soft navigation.
    await financePage.reload();
    await financePage.getByLabel('Product').selectOption({ label: optionLabel });
    await expect(financePage.getByText('Active plan')).toBeVisible({ timeout: 15_000 });

    await financeContext.close();
  });

  test('accrues a real commission statement from a real policy, and requesting payout on it genuinely 409s while OPEN', async ({
    browser,
  }) => {
    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const financePage = await financeContext.newPage();

    const agentId = await onboardRealAgent(financePage);
    const { optionLabel } = await createRealActiveProduct(financePage);

    await financePage.goto(`/staff/agents/${agentId}`);
    await financePage.getByLabel('Product').selectOption({ label: optionLabel });
    await expect(
      financePage.getByText('No plan applies to this agent for this product yet.'),
    ).toBeVisible();
    await createDefaultPlan(financePage, '0.1000');
    await expect(financePage.getByText('Active plan')).toBeVisible({ timeout: 15_000 });

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
    await expect(financePage.getByRole('alert')).toBeVisible({ timeout: 15_000 });

    await financeContext.close();
  });
});
