import { expect, type Page } from '@playwright/test';
import { dmy } from './dates';

/**
 * Registering a client through the agent console, with everything the platform demands.
 *
 * An agent may not register a half-known person: `PartyController` refuses one with no sex
 * ("a policy cannot be priced for a life whose sex is unrecorded") and one with no identity
 * document ("a client who cannot be identified cannot be KYC-verified"), and the form asks for
 * both before it will submit. Four specs had been filling a name and a date of birth only --
 * the shape that was sufficient before those rules existed -- and each failed on an invisible
 * "Registered" that never arrived, because the submit never left the form.
 *
 * Hence one helper rather than four copies of the field list: the next rule added to this form
 * breaks one function, not every spec that happens to need a client.
 */
export async function registerIndividualAsAgent(
  page: Page,
  fullName: string,
  options: { dateOfBirth?: string; phoneNumber?: string } = {},
): Promise<void> {
  await page.getByLabel('Full name').fill(fullName);
  await page.getByLabel('Date of birth').fill(dmy(options.dateOfBirth ?? '1990-05-12'));
  if (options.phoneNumber) {
    await page.getByLabel('Phone number (optional)').fill(options.phoneNumber);
  }

  // Required of an agent, and unique per tenant -- `ux_party_identity_document` refuses two
  // clients sharing a national ID, so the number is derived from the caller's own unique name.
  await page.getByLabel('ID type').selectOption('NATIONAL_ID');
  await page.getByLabel('ID number').fill(`E2E-NIDA-${fullName.replace(/\D/g, '').slice(-13) || Date.now()}`);
  await page.getByLabel('Sex').selectOption('FEMALE');

  await page.getByRole('button', { name: 'Register individual' }).click();
  await expect(page.getByText('Registered', { exact: true })).toBeVisible({ timeout: 15_000 });
}
