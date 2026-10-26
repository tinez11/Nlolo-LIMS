import type { Page } from '@playwright/test';

/**
 * Picks a product in the console's product chooser (2026-10-26), which replaced the "Name (CODE)" select on every
 * screen that sells, quotes or plans a product. Searching rather than clicking a category first: the search looks
 * across every category, so a spec does not have to know where a product is filed -- only its option label, which
 * is still "Name (CODE)", now the accessible name of the product's button.
 *
 * A screen may already have chosen one -- issuing from a decided case takes the case's product -- in which case the
 * chooser is collapsed to that product: kept if it is this one, reopened with Change if not.
 */
export async function chooseProduct(page: Page, label: string): Promise<void> {
  const match = /^(.*?)\s*\(([^)]+)\)\s*$/.exec(label);
  const name = match?.[1] ?? label;
  const code = match?.[2] ?? label;
  const change = page.getByRole('button', { name: /^Change product, / });
  if (await change.isVisible()) {
    if ((await change.getAttribute('aria-label')) === `Change product, ${name} chosen`) return;
    await change.click();
  }
  await page.getByRole('searchbox', { name: 'Find a product' }).fill(code);
  await page.getByRole('button', { name: label, exact: true }).click();
}
