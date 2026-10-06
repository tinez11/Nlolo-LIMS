import { expect, test } from '@playwright/test';

/**
 * IFRS 17 I3a through the real stack: finance reads the posting rules every event posts by, and the queue of events
 * they could not post. Retry and dismiss are proven in the backend's integration and contract tests -- no test-only
 * endpoint exists to put an event in the queue here.
 */

test.describe('IFRS 17 posting rules', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('the rules in force are listed by event, read-only, and searchable', async ({ page }) => {
    await page.goto('/staff/posting-rules');
    await expect(page.getByRole('heading', { name: 'Posting rules' })).toBeVisible();
    await expect(page.getByText(/posting-rules v\d+/)).toBeVisible();

    const renewal = page.getByRole('table', { name: 'Rule A-06' });
    await expect(renewal).toContainText('2122');
    await expect(renewal).toContainText('PRM_REN');
    await expect(page.getByRole('table', { name: 'Rule I-01' })).toContainText('2142');

    await page.getByLabel('Search rules').fill('4160');
    await expect(page.getByRole('table', { name: 'Rule I-03' })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Rule A-06' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /edit|save/i })).toHaveCount(0);
  });

  test('the unposted events queue renders', async ({ page }) => {
    await page.goto('/staff/unposted-events');
    await expect(page.getByRole('heading', { name: 'Unposted events' })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Waiting to post' })).toBeVisible();
  });
});
