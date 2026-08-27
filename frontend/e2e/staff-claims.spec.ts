import { expect, test, type Page } from '@playwright/test';
import { dmy } from './dates';

/**
 * Claims e2e coverage against the real stack.
 *
 * Two things this suite deliberately does NOT try to prove, because they are
 * genuinely unreachable in this environment, verified before writing a line of
 * UI code rather than discovered by a failing test:
 *
 *  - A successful claim registration. `ClaimsApiImpl.registerClaim` requires the
 *    policy to be "in force" (ACTIVE or REINSTATED), and the only seeded policy,
 *    POL-6BD5702F, is SURRENDERED -- confirmed with a real curl POST before any
 *    code was written (`"Policy POL-6BD5702F was not in force on 2026-08-01"`,
 *    422). This is a DATA limitation, not an architectural one (unlike loan
 *    origination, which is unconditionally blocked platform-wide): a genuinely
 *    ACTIVE policy would succeed. So the registration test below proves the real
 *    422 is surfaced correctly instead, which needs no mocking at all -- the
 *    network call genuinely fires and the backend genuinely rejects it.
 *  - Assessment, settlement decision, or reopen. Not built in this slice at all
 *    (deliberately out of scope), so there is nothing to test yet.
 */

const CLAIM_ID = '0dca2710-f8c2-4c10-8f15-c2c801b8ea67'; // the one seeded DEATH claim

async function firstClaimRow(page: Page) {
  const table = page.getByRole('table', { name: 'Claims' });
  const empty = page.getByText('No claims yet');
  await expect(table.or(empty)).toBeVisible();
  if (await empty.isVisible()) return null;
  const row = table.getByRole('button').first();
  await expect(row).toBeVisible();
  return row;
}

test.describe('staff claims', () => {
  test('lands on the claims list with the real seeded DEATH claim', async ({ page }) => {
    await page.goto('/staff/claims');
    await expect(page.getByRole('heading', { name: 'Claims' })).toBeVisible();

    const row = await firstClaimRow(page);
    expect(row).not.toBeNull();
    await expect(row!).toContainText('DEATH');
  });

  test('the tenant_id claim reaches the backend for a paged, real endpoint', async ({ page }) => {
    const responsePromise = page.waitForResponse(
      (r) => r.url().includes('/claims') && r.request().method() === 'GET' && !r.url().includes('/evidence'),
      { timeout: 30_000 },
    );
    await page.goto('/staff/claims');
    const response = await responsePromise;
    expect(response.status()).toBe(200);
  });

  test('opens a row preview showing real DEATH-claim details, then navigates to full detail', async ({
    page,
  }) => {
    await page.goto('/staff/claims');
    // Search for the seeded claim specifically, by its own policy number --
    // "first row" stopped reliably meaning "the seeded claim" once the list
    // defaults to newest-created-first and other specs' fixtures create newer
    // claims ahead of it.
    await page.getByPlaceholder('Search by policy number').fill('POL-6BD5702F');
    await expect(page).toHaveURL(/q=POL-6BD5702F/, { timeout: 5000 });
    const row = await firstClaimRow(page);
    expect(row).not.toBeNull();
    await row!.click();

    const drawer = page.getByRole('dialog', { name: 'DEATH' });
    await expect(drawer).toBeVisible();
    // Real seeded data, not a placeholder -- the exact fields the seeder wrote.
    await expect(drawer).toContainText('Natural causes');
    await expect(drawer).toContainText('Dar es Salaam');
    await expect(drawer).toContainText('Dr. Juma');

    // No mutating action lives in the drawer -- this slice never built one, but
    // the invariant is worth asserting the same way PolicyDrawer's is.
    await expect(drawer.getByRole('button', { name: /approve|reject|settle|reopen/i })).toHaveCount(0);

    await drawer.getByRole('link', { name: /full detail/i }).click();
    await expect(page).toHaveURL(new RegExp(`/staff/claims/${CLAIM_ID}`));
    await expect(page.getByRole('heading', { name: 'DEATH' })).toBeVisible();
    await expect(page.getByText('Natural causes')).toBeVisible();
  });

  test('a filter is shareable through the URL', async ({ page }) => {
    await page.goto('/staff/claims?status=REOPENED');
    await expect(page.getByRole('heading', { name: 'Claims' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Reopened', pressed: true })).toBeVisible();
    // The one seeded claim really is REOPENED, so filtering to it must not empty the table.
    await expect(page.getByText('No claims yet')).not.toBeVisible();
  });

  test('registering against the one seeded (SURRENDERED) policy genuinely 422s, client validation intact', async ({
    page,
  }) => {
    await page.goto('/staff/claims/new');
    await expect(page.getByRole('heading', { name: 'Register a claim' })).toBeVisible();

    // Submitting fully blank first -- every required field's client-side message
    // should appear, and nothing should reach the network.
    let requestFired = false;
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().endsWith('/claims')) requestFired = true;
    });
    await page.getByRole('button', { name: 'Register claim' }).click();
    await expect(page.getByText('Policy number is required')).toBeVisible();
    expect(requestFired).toBe(false);

    // Now fill a genuinely well-formed DEATH claim against the real seeded
    // policy and claimant -- passes every client rule, so this DOES reach the
    // network, and the backend's real business rule rejects it.
    await page.getByPlaceholder('POL-XXXXXXXX').fill('POL-6BD5702F');
    await page.getByRole('button', { name: 'Search for the claimant by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('Amina');
    await page.getByText('Amina Owner').click();
    await page.getByLabel('Date of event').fill(dmy('2026-08-01'));
    await page.getByLabel('Cause of death').fill('Test');
    await page.getByLabel('Place of death').fill('Test');
    await page.getByLabel('Date of death').fill(dmy('2026-08-01'));
    await page.getByLabel('Attending physician').fill('Dr. Test');

    await page.getByRole('button', { name: 'Register claim' }).click();

    await expect(page.getByRole('alert')).toContainText(/was not in force/i);
    // Still on the register page -- a rejected submission must not navigate away.
    await expect(page.getByRole('heading', { name: 'Register a claim' })).toBeVisible();
  });

  test('does not resurface a stale registration error on a fresh visit to the page', async ({
    page,
  }) => {
    await page.goto('/staff/claims/new');
    await page.getByPlaceholder('POL-XXXXXXXX').fill('POL-6BD5702F');
    await page.getByRole('button', { name: 'Search for the claimant by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('Amina');
    await page.getByText('Amina Owner').click();
    await page.getByLabel('Date of event').fill(dmy('2026-08-01'));
    await page.getByLabel('Cause of death').fill('Test');
    await page.getByLabel('Place of death').fill('Test');
    await page.getByLabel('Date of death').fill(dmy('2026-08-01'));
    await page.getByLabel('Attending physician').fill('Dr. Test');
    await page.getByRole('button', { name: 'Register claim' }).click();
    await expect(page.getByRole('alert')).toBeVisible();

    // Leave and come back -- a fresh mount of the same singleton `registering`
    // resource must not carry the previous attempt's rejection forward.
    await page.goto('/staff/claims');
    await page.goto('/staff/claims/new');
    await expect(page.getByRole('alert')).not.toBeVisible();
  });

  test('the search bar finds a real claim by its policy number', async ({ page }) => {
    await page.goto('/staff/claims');
    await expect(page.getByRole('heading', { name: 'Claims' })).toBeVisible();
    await page.getByPlaceholder('Search by policy number').fill('POL-6BD5702F');
    await expect(page).toHaveURL(/q=POL-6BD5702F/, { timeout: 5000 });
    await expect(page.getByText('DEATH')).toBeVisible();
  });
});
