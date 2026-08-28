import { expect, test } from '@playwright/test';
import { dmy } from './dates';
import { expectStaffShellReady } from './guards';

/**
 * The client register, and one client's whole record.
 *
 * This file was `staff-kyc-review.spec.ts` and covered a KYC work queue. The screen
 * became a register: it is named Clients, it shows everyone by default instead of
 * only those awaiting KYC, it is searchable by name, and drilling into a row now
 * opens seven panels rather than two. The queue did not disappear -- the nav badge
 * still counts parties awaiting KYC -- so the KYC coverage below is kept rather than
 * replaced.
 *
 * `GET /parties` was the endpoint that made any of this possible: before it, a party
 * PENDING KYC with nothing referencing it (a fresh self-service or agent-assisted
 * registration) was invisible to staff, with no way to find it at all.
 */
test.describe('staff clients register', () => {
  test('shows every client by default, not only those awaiting KYC', async ({ page }) => {
    await page.goto('/staff/kyc');
    await expectStaffShellReady(page);
    await expect(page.getByRole('heading', { name: 'Clients' })).toBeVisible({ timeout: 30_000 });

    // The substance of the rename. A bare visit carries no filter, so a register
    // called Clients cannot be showing only the few dozen awaiting a KYC decision.
    await expect(page).not.toHaveURL(/kycStatus=/);
    await expect(page.getByText('All clients')).toBeVisible();

    const table = page.getByRole('table', { name: 'Clients' });
    await expect(table).toBeVisible({ timeout: 15_000 });

    // The count on the unfiltered register must EXCEED the pending count. Asserting a
    // VERIFIED row is on screen would have been the obvious check and a worse one: it
    // depends on where verified clients happen to fall in a newest-first first page,
    // and in this tenant recent registrations are all pending, so it failed while the
    // screen was behaving correctly. Comparing the two totals tests the actual claim
    // -- that the default filter is not PENDING -- whatever the data looks like.
    // Each count is read after its OWN page load, not by clicking a filter and
    // reading again. The stat card deliberately keeps showing the previous total
    // while the next list is in flight -- so clicking Pending and reading
    // immediately returns the ALL count under the Pending label, which is how this
    // assertion first "proved" that 67 was greater than 67.
    const statTotal = async (label: string) => {
      const card = page.getByText(label, { exact: true }).locator('..');
      const text = (await card.textContent()) ?? '';
      return Number(text.replace(label, '').match(/([\d,]+)/)?.[1]?.replace(/,/g, '') ?? '0');
    };

    const totalAll = await statTotal('All clients');

    await page.goto('/staff/kyc?kycStatus=PENDING');
    await expect(page.getByRole('table', { name: 'Clients' })).toBeVisible({ timeout: 15_000 });
    const totalPending = await statTotal('Pending clients');

    expect(totalPending).toBeGreaterThan(0);
    expect(totalAll).toBeGreaterThan(totalPending);
  });

  test('finds a freshly agent-registered PENDING client, and the KYC filter still round-trips', async ({
    page,
    browser,
  }) => {
    const agentContext = await browser.newContext({ storageState: 'e2e/.auth/agent.json' });
    const agentPage = await agentContext.newPage();

    const fullName = `E2E Clients Fixture ${Date.now()}`;
    await agentPage.goto('/agents/customers/new');
    await agentPage.getByLabel('Full name').fill(fullName);
    await agentPage.getByLabel('Date of birth').fill(dmy('1990-05-12'));
    await agentPage.getByRole('button', { name: 'Register individual' }).click();
    await expect(agentPage.getByText('Registered', { exact: true })).toBeVisible({ timeout: 15_000 });
    const partyIdText = await agentPage.getByText(/^[0-9a-f]{8}-[0-9a-f]{4}-/).textContent();
    const partyId = (partyIdText ?? '').trim();
    await agentContext.close();

    // Searchable by name. A register of every client the tenant has ever registered
    // is not browsable, so this is how anyone actually finds a person -- and it is
    // also what makes this assertion independent of how many clients exist.
    await page.goto('/staff/kyc');
    await expectStaffShellReady(page);
    await page.getByLabel('Search by name').fill(fullName);
    await page.getByLabel('Search by name').press('Enter');
    await expect(page).toHaveURL(/[?&]q=/, { timeout: 10_000 });
    await expect(page.getByText(fullName)).toBeVisible({ timeout: 15_000 });

    // The KYC filter still narrows, and still round-trips through the URL.
    await page.getByText('Pending', { exact: true }).first().click();
    await expect(page).toHaveURL(/kycStatus=PENDING/);
    await expect(page.getByText(fullName)).toBeVisible({ timeout: 15_000 });

    await page.getByText(fullName).click();
    await expect(page).toHaveURL(`/staff/parties/${partyId}`);
    await expect(page.getByRole('heading', { level: 2, name: 'KYC verification' })).toBeVisible();
  });

  test('a client record shows every panel, populated from real data', async ({ page }) => {
    // Amina Owner is the seeded policyholder every other suite issues against, so
    // this client genuinely holds policies -- the panels are not empty scaffolding.
    await page.goto('/staff/kyc');
    await expectStaffShellReady(page);
    await page.getByLabel('Search by name').fill('Amina Owner');
    await page.getByLabel('Search by name').press('Enter');
    await expect(page.getByText('Amina Owner').first()).toBeVisible({ timeout: 15_000 });
    await page.getByText('Amina Owner').first().click();
    await expect(page).toHaveURL(/\/staff\/parties\/[0-9a-f-]{36}$/, { timeout: 15_000 });

    for (const panel of [
      'Policies',
      'Claims',
      'Underwriting',
      'Named as beneficiary',
      'KYC verification',
      'Identity',
      'Documents',
      'Also an agent',
    ]) {
      await expect(page.getByRole('heading', { name: panel, exact: true })).toBeVisible();
    }

    // Identity fields that were stored since the first migration and returned by no
    // endpoint at all until PartyDetailView existed.
    await expect(page.getByText('Date of birth')).toBeVisible();
    await expect(page.getByText('Registered by')).toBeVisible();

    // A real policy, linked.
    await expect(page.getByRole('link', { name: /^POL-/ }).first()).toBeVisible({ timeout: 20_000 });

    // This client holds hundreds of policies, so the panel caps and says so rather
    // than burying every panel beneath it. The link has to go somewhere real.
    await expect(page.getByText(/more not shown/).first()).toBeVisible();
    await page.getByRole('link', { name: 'View all' }).click();
    await expect(page).toHaveURL(/policyholderPartyId=/);
    await expect(page.getByRole('heading', { name: 'Policies' })).toBeVisible({ timeout: 15_000 });
  });
});

test.describe('agents my clients', () => {
  test.use({ storageState: 'e2e/.auth/agent.json' });

  test('sees the clients it registered, and a record without staff-only panels', async ({ page }) => {
    await page.goto('/agents/clients');
    await expect(page.getByRole('heading', { name: 'My clients' })).toBeVisible({ timeout: 30_000 });

    const table = page.getByRole('table', { name: 'Clients' });
    await expect(table).toBeVisible({ timeout: 15_000 });
    await table.getByRole('cell').first().click();
    await expect(page).toHaveURL(/\/agents\/parties\/[0-9a-f-]{36}$/, { timeout: 15_000 });

    // "Your Policies", not "Policies": an agent's policies are scoped to their
    // hierarchy team's book while this register is built on who they REGISTERED, so
    // an empty panel here means "not in your book" rather than "this client has none".
    await expect(page.getByRole('heading', { name: 'Your Policies' })).toBeVisible();

    // Staff-only panels must not render for an agent. `POST /parties/{id}/kyc` is
    // staff-only and `GET /agents` is staff-only, so showing either here would be a
    // control that 403s on use.
    await expect(page.getByRole('heading', { name: 'KYC verification' })).not.toBeVisible();
    await expect(page.getByRole('heading', { name: 'Also an agent' })).not.toBeVisible();
  });
});
