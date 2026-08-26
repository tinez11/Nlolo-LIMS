import { expect, test } from '@playwright/test';

/**
 * Agents-realm e2e coverage against the real backend, as `agent.senior`
 * (seeded by `backend/scripts/seed-dev-data.sh` -- see auth-agent.setup.ts).
 *
 * This is the realm's first slice: "my profile" (resolved via the real
 * `GET /agents/me`, added specifically because no other way existed for an
 * agent token to discover its own agentId) and "onboard a customer"
 * (`POST /parties/individuals` / `.../corporates`, both already agent-scoped
 * server-side before any frontend called either).
 */
test.describe('agents my profile', () => {
  test('lands on its own profile via GET /agents/me, with real commission panels', async ({ page }) => {
    await page.goto('/agents/me');

    await expect(page.getByRole('heading', { name: 'My profile' })).toBeVisible();
    // A real license number from the seed script, not a placeholder.
    await expect(page.getByText(/LIC-SENIOR/)).toBeVisible();
    await expect(page.getByText('Top of hierarchy')).toBeVisible();

    // Commission plan panel: selecting a product drives a real
    // GET /agents/{agentId}/commission-plan?productId=... round trip.
    await expect(page.getByText('Commission plan')).toBeVisible();
    await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
    await expect(page.getByText('Resolving plan')).not.toBeVisible({ timeout: 15_000 });

    // No "create plan" / "request payout" affordance anywhere -- those are
    // FINANCE_OFFICER/ADMIN staff actions, and canManage is hardcoded false
    // on this page.
    await expect(page.getByRole('button', { name: 'Create plan' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Request payout' })).toHaveCount(0);
  });

  test('a fresh navigation to /agents lands on the same profile, not a realm picker dead end', async ({ page }) => {
    await page.goto('/agents');
    await expect(page).toHaveURL(/\/agents\/me$/);
    await expect(page.getByRole('heading', { name: 'My profile' })).toBeVisible();
  });
});

test.describe('agents onboard a customer', () => {
  test('registers a real individual, PENDING KYC, then resets for another', async ({ page }) => {
    await page.goto('/agents/customers/new');
    await expect(page.getByRole('heading', { name: 'Onboard a customer' })).toBeVisible();

    const suffix = Date.now();
    await page.getByLabel('Full name').fill(`E2E Agent Onboarded ${suffix}`);
    await page.getByRole('button', { name: 'Select the date of birth' }).click();
    await page.getByPlaceholder('YYYY-MM-DD').fill('1990-05-12');
    await page.getByLabel('Phone number (optional)').fill('+255712345678');
    await page.getByRole('button', { name: 'Register individual' }).click();

    await expect(page.getByText('Registered', { exact: true })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/^[0-9a-f]{8}-[0-9a-f]{4}-/)).toBeVisible();
    await expect(page.getByText('Pending', { exact: true })).toBeVisible();

    await page.getByRole('button', { name: 'Register another' }).click();
    await expect(page.getByLabel('Full name')).toBeVisible();
  });

  test('registers a real corporate under the same agent-assisted path', async ({ page }) => {
    await page.goto('/agents/customers/new');
    await page.getByRole('button', { name: 'Corporate' }).click();

    const suffix = Date.now();
    await page.getByLabel('Registered name').fill(`E2E Corporate ${suffix}`);
    await page.getByLabel('Registration number').fill(`E2E-REG-${suffix}`);
    await page.getByRole('button', { name: 'Register corporate' }).click();

    await expect(page.getByText('Registered', { exact: true })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('Pending', { exact: true })).toBeVisible();
  });

  test('rejects a duplicate corporate registration number with a real 409', async ({ page }) => {
    const registrationNumber = `E2E-DUP-${Date.now()}`;

    await page.goto('/agents/customers/new');
    await page.getByRole('button', { name: 'Corporate' }).click();
    await page.getByLabel('Registered name').fill('E2E Duplicate First');
    await page.getByLabel('Registration number').fill(registrationNumber);
    await page.getByRole('button', { name: 'Register corporate' }).click();
    await expect(page.getByText('Registered', { exact: true })).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'Register another' }).click();
    await page.getByLabel('Registered name').fill('E2E Duplicate Second');
    await page.getByLabel('Registration number').fill(registrationNumber);
    await page.getByRole('button', { name: 'Register corporate' }).click();

    await expect(page.getByRole('alert')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('Registered', { exact: true })).not.toBeVisible();
  });
});
