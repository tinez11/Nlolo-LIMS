import type { Page } from '@playwright/test';
import { dmy } from './dates';

/**
 * The publish-version form is a set of steps since 2026-10-22 (Product type, Basics, Who can buy, Pricing or Rates,
 * What it pays, Charges, Review): a field is on screen only while its own step is open.
 */
export async function step(page: Page, name: string) {
  await page.getByRole('list', { name: 'Steps' }).getByRole('button', { name: new RegExp(`^${name}`) }).click();
}

/** Opens Review and presses Publish version. The confirmation ("Publish and make active") stays with the caller. */
export async function publishVersion(page: Page) {
  await step(page, 'Review');
  await page.getByRole('button', { name: 'Publish version' }).click();
}

/** Effective date and TIRA filing: the Basics every version needs. */
export async function fillBasics(page: Page, tiraReference: string) {
  await step(page, 'Basics');
  await page.getByLabel('Effective date').fill(dmy('2026-01-01'));
  await page.getByLabel('TIRA filing reference').fill(tiraReference);
  await page.getByLabel('TIRA approval date').fill(dmy('2026-01-15'));
}
