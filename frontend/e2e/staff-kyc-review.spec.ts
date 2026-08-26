import { expect, test } from '@playwright/test';

/**
 * `GET /parties` -- there was genuinely no way to find a party at all before
 * this (confirmed by reading `PartyRepository`, which had exactly one query
 * method: `findByTenantIdAndRegistrationNumber`): a party PENDING KYC with
 * nothing yet referencing it (a fresh self-service or agent-assisted
 * registration) was invisible to staff, with no way to discover it to
 * review. `staff-party-kyc.spec.ts` covers the OTHER discovery path
 * (drilling in from a policy that already names a party); this covers the
 * genuinely new one.
 *
 * The fixture party is registered through the real agents-realm onboarding
 * flow (a separate real login, `e2e/.auth/agent.json`), not seeded directly
 * -- proving the whole loop end to end: an agent onboards a customer, and
 * staff can actually find it afterward.
 */
test.describe('staff KYC review', () => {
  test('finds a freshly agent-registered PENDING party through the review queue, not a policy drill-in', async ({
    page,
    browser,
  }) => {
    const agentContext = await browser.newContext({ storageState: 'e2e/.auth/agent.json' });
    const agentPage = await agentContext.newPage();

    const fullName = `E2E KYC Queue Fixture ${Date.now()}`;
    await agentPage.goto('/agents/customers/new');
    await agentPage.getByLabel('Full name').fill(fullName);
    await agentPage.getByRole('button', { name: 'Select the date of birth' }).click();
    await agentPage.getByPlaceholder('YYYY-MM-DD').fill('1990-05-12');
    await agentPage.getByRole('button', { name: 'Register individual' }).click();
    await expect(agentPage.getByText('Registered', { exact: true })).toBeVisible({ timeout: 15_000 });
    const partyIdText = await agentPage.getByText(/^[0-9a-f]{8}-[0-9a-f]{4}-/).textContent();
    const partyId = (partyIdText ?? '').trim();
    expect(partyId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    await agentContext.close();

    // Defaults to the PENDING filter -- the whole point of this screen is
    // "what needs my attention right now", not a firehose of every party.
    await page.goto('/staff/kyc');
    await expect(page.getByRole('heading', { name: 'KYC review' })).toBeVisible();
    await expect(page.getByText(fullName)).toBeVisible();

    await page.getByText(fullName).click();
    await expect(page).toHaveURL(`/staff/parties/${partyId}`);
    await expect(page.getByRole('heading', { level: 2, name: 'KYC verification' })).toBeVisible();
  });

  test('the kycStatus filter round-trips through a real request and the URL', async ({ page }) => {
    await page.goto('/staff/kyc');
    await expect(page.getByRole('heading', { name: 'KYC review' })).toBeVisible();
    // Defaults to PENDING -- no kycStatus param on a bare visit.
    await expect(page).not.toHaveURL(/kycStatus=/);

    await page.getByText('Verified', { exact: true }).click();
    await expect(page).toHaveURL(/kycStatus=VERIFIED/);
    await expect(page.getByText('Verified parties', { exact: false })).toBeVisible();

    // "All" writes an explicit ?kycStatus=ALL sentinel, not just an absent
    // param -- otherwise it would be indistinguishable from a fresh visit
    // (which defaults to PENDING) and immediately snap back.
    await page.getByText('All', { exact: true }).click();
    await expect(page).toHaveURL(/kycStatus=ALL/);
    await expect(page.getByText('All parties', { exact: false })).toBeVisible();
  });
});
