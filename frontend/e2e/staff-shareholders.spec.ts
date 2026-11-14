import { expect, test } from '@playwright/test';
import { approverPage } from './approverSession';

/**
 * The shareholders register through the real stack (2026-11-14): finance adds a shareholder and records an issue of
 * shares; it waits for a second person, who approves it from the Approvals queue's link; the books and the register
 * then agree.
 */
test.describe('Shareholders register', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('finance records an issue of shares, a finance approver posts it, and the register agrees with the books', async ({
    page,
    browser,
  }) => {
    // Two people sign in, and the approver's session is opened fresh: 60 seconds ran out mid-approval (2026-11-14).
    test.slow();
    await page.goto('/staff');
    await page.getByRole('link', { name: 'Shareholders' }).click();
    await expect(page.getByRole('heading', { name: 'Shareholders' })).toBeVisible();

    // A fresh database has no nominal value yet; the dev seed sets TZS 1,000.
    const prompt = page.getByText('Set the nominal value of ordinary shares');
    if (await prompt.isVisible()) {
      await page.getByLabel(/^Nominal value per share/).fill('1000');
      await page.getByRole('button', { name: 'Save' }).click();
      await expect(prompt).toBeHidden();
    }

    const name = `E2E Holder ${Date.now()}`;
    await page.getByRole('button', { name: 'Add shareholder' }).click();
    await page.getByLabel(/^Name/).fill(name);
    await page.getByLabel(/^ID or company registration number/).fill(`E2E-${Date.now()}`);
    await page.getByLabel(/^Tanzanian or foreign/).selectOption('yes');
    await page.getByRole('button', { name: 'Add shareholder' }).last().click();
    await expect(page.getByRole('row', { name: new RegExp(name) })).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'Record an issue' }).click();
    await page.getByLabel(/^Shareholder/).selectOption({ label: name });
    const nominal = (await page.getByLabel(/^Class/).locator('option').first().textContent()) ?? '';
    const price = nominal.match(/TZS ([\d,]+)\.\d\d/)?.[1]?.replace(/,/g, '') ?? '1000';
    await page.getByLabel(/^Number of shares/).fill('10');
    await page.getByLabel(/^Price per share/).fill(price);
    await page.getByLabel(/^Board resolution or allotment/).fill('E2E allotment');
    await page.getByRole('button', { name: 'Record issue' }).click();

    const waiting = page.getByRole('region', { name: 'Waiting for approval' }).or(page.getByText('Waiting for approval'));
    await expect(waiting.first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(`shares to ${name}`)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Withdraw' })).toBeVisible();

    const { page: approver, context } = await approverPage(browser);
    await approver.goto('/staff/approvals');
    await expect(approver.getByText(`10 ordinary shares to ${name}`).first()).toBeVisible({ timeout: 15_000 });
    await approver.goto('/staff/shareholders');
    const row = approver.locator('li', { hasText: `shares to ${name}` });
    await row.getByRole('button', { name: 'Approve and post' }).click();
    await row.getByRole('button', { name: 'Post to the ledger' }).click();
    await expect(approver.getByText(`shares to ${name}`)).toBeHidden({ timeout: 15_000 });
    await context.close();

    await page.reload();
    const holder = page.getByRole('table', { name: 'Shareholders' }).getByRole('row', { name: new RegExp(name) });
    await expect(holder.getByRole('cell', { name: '10', exact: true })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('Register agrees with the books').locator('..')).toContainText('Met');
  });
});
