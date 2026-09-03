import { expect, test } from '@playwright/test';

/**
 * `GET /underwriting/cases` -- the queue that closes the gap where a created
 * case had nowhere to browse back to (staff-underwriting.spec.ts's own module
 * doc comment documents the OLD state this fixes: "no list/search endpoint").
 */

/**
 * Opens a real case and returns both of its identifiers.
 *
 * The queue is keyed on the PROPOSAL NUMBER now, not the case id — a bare uuid is not
 * something anyone can quote, which is why the column changed. The proposal number is
 * minted server-side, so the only way to learn it is to read it off the case that was
 * just created, which is what the detail page shows.
 *
 * The picker is scoped to its listbox: this page now has TWO PartyPickers (applicant and
 * life assured), so an unscoped getByText for a party name is ambiguous the moment both
 * dropdowns can hold the same person.
 */
async function openRealCase(
  page: import('@playwright/test').Page,
): Promise<{ caseId: string; proposalNumber: string }> {
  await page.goto('/staff/underwriting/new');
  await page.getByRole('button', { name: 'Search for the applicant by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill('Amina');
  await page.getByRole('option', { name: 'Amina Owner' }).click();
  await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
  await expect(page.getByText('Resolving product version…')).not.toBeVisible();
  await page.getByLabel('Sum assured').fill('1500000.00');
  await page.getByRole('button', { name: 'Open case' }).click();
  await expect(page).toHaveURL(/\/staff\/underwriting\/[0-9a-f-]{36}$/, { timeout: 15_000 });

  const caseId = page.url().split('/').pop() as string;
  const proposalNumber = (
    await page.getByText(/^PRO-[0-9A-F]{8}$/).first().textContent()
  )?.trim();
  expect(proposalNumber, 'a freshly opened case must be given a proposal number').toBeTruthy();
  return { caseId, proposalNumber: proposalNumber as string };
}

test.describe('staff underwriting queue', () => {
  test('a freshly opened case appears in the queue, previews in the drawer, and drills in to full detail', async ({
    page,
  }) => {
    const { caseId, proposalNumber } = await openRealCase(page);

    await page.goto('/staff/underwriting');
    await expect(page.getByRole('heading', { name: 'Underwriting' })).toBeVisible();
    // The row is found by its proposal number, which is the identifying column now.
    await expect(page.getByText(proposalNumber)).toBeVisible();

    await page.getByText(proposalNumber).click();
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

    // The detail page's own callout now correctly links back to the queue,
    // replacing the old (now-false) "bookmark this, there's no other way
    // back" copy.
    await page.getByRole('link', { name: 'Underwriting queue' }).click();
    await expect(page).toHaveURL(/\/staff\/underwriting$/);
    await expect(page.getByText(proposalNumber)).toBeVisible();
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
