import { expect, test } from '@playwright/test';

/**
 * The management overview (2026-11-12, analytics plan 5), as `staff.management` -- the Management role: sees every
 * area and the overview, changes nothing. And a job role (`staff.manager`, CLAIMS_MANAGER) gets no Overview tab.
 *
 * Proves the round trip through the real backend, not the dev data's totals.
 */
test.describe('staff management overview', () => {
  test('Management opens on the overview, each card opens what it counted, and nothing can be changed', async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'e2e/.auth/staff-management.json' });
    const page = await context.newPage();
    await page.goto('/staff/dashboard');

    await expect(page.getByRole('tab', { name: 'Overview', selected: true })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('Premiums collected')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('New policies', { exact: true })).toBeVisible();
    await expect(page.getByText('Collection rate')).toBeVisible();
    await expect(page.getByText('Waiting for a decision')).toBeVisible();

    await page.getByRole('link', { name: 'Policies in force' }).click();
    await expect(page).toHaveURL(/\/staff\/reports\/policies-in-force$/);

    // Sees the policies, but cannot issue one: the role changes nothing (the link needs policies.issue).
    await page.goto('/staff/policies');
    await expect(page.getByRole('heading', { name: 'Policies', level: 1 })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('link', { name: 'Issue policy' })).toHaveCount(0);
    await context.close();
  });

  test('a claims manager has no overview tab', async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'e2e/.auth/staff-manager.json' });
    const page = await context.newPage();
    await page.goto('/staff/dashboard');

    await expect(page.getByRole('heading', { name: 'Dashboard', exact: true })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('Premiums collected')).toHaveCount(0);
    await expect(page.getByRole('tab', { name: 'Overview' })).toHaveCount(0);
    await context.close();
  });
});
