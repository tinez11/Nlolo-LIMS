import { expect, test } from '@playwright/test';

/**
 * `GET /underwriting/cases` -- the queue that closes the gap where a created
 * case had nowhere to browse back to (staff-underwriting.spec.ts's own module
 * doc comment documents the OLD state this fixes: "no list/search endpoint").
 */

async function openRealCase(page: import('@playwright/test').Page): Promise<string> {
  await page.goto('/staff/underwriting/new');
  await page.getByRole('button', { name: 'Search for the applicant by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill('Amina');
  await page.getByText('Amina Owner').click();
  await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
  await expect(page.getByText('Resolving product version…')).not.toBeVisible();
  await page.getByLabel('Sum assured').fill('1500000.00');
  await page.getByRole('button', { name: 'Open case' }).click();
  await expect(page).toHaveURL(/\/staff\/underwriting\/[0-9a-f-]{36}$/, { timeout: 15_000 });
  return page.url().split('/').pop() as string;
}

test.describe('staff underwriting queue', () => {
  test('a freshly opened case appears in the queue, previews in the drawer, and drills in to full detail', async ({
    page,
  }) => {
    const caseId = await openRealCase(page);

    await page.goto('/staff/underwriting');
    await expect(page.getByRole('heading', { name: 'Underwriting' })).toBeVisible();
    await expect(page.getByText(caseId)).toBeVisible();

    await page.getByText(caseId).click();
    // The dialog's accessible name comes from its own Dialog.Title ("Underwriting
    // case") via Radix's automatic aria-labelledby wiring, which takes precedence
    // over SheetContent's aria-label prop -- confirmed against the real rendered
    // DOM, not assumed.
    const drawer = page.getByRole('dialog', { name: 'Underwriting case' });
    await expect(drawer).toBeVisible();
    await expect(drawer.getByText('Open', { exact: true })).toBeVisible();

    await drawer.getByRole('link', { name: /full detail/i }).click();
    await expect(page).toHaveURL(new RegExp(`/staff/underwriting/${caseId}`));
    await expect(page.getByRole('heading', { name: 'Underwriting case' })).toBeVisible();
  });

  test('the status filter is shareable through the URL', async ({ page }) => {
    await openRealCase(page);

    await page.goto('/staff/underwriting');
    await expect(page.getByRole('heading', { name: 'Underwriting' })).toBeVisible();
    await page.getByRole('button', { name: 'Open', exact: true }).click();
    await expect(page).toHaveURL(/status=OPEN/);
    // The case just opened above is genuinely OPEN, so filtering to it must not empty the table.
    await expect(page.getByText('No cases yet')).not.toBeVisible();
  });
});
