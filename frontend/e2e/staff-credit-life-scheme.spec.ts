import { expect, test } from '@playwright/test';
import { ENROLMENT_PASSING, ENROLMENT_REFUSED, seedCreditLifeScheme } from './creditLife';

/**
 * A lender's month on a credit-life scheme, in a browser, against the real stack.
 *
 * Four claims this page makes that nothing cheaper can check:
 *
 * 1. **An uploaded file has enrolled nobody.** A submission is PENDING until a SECOND person
 *    accepts it, and the screen must not let "uploaded" read as "done". The proof is not that a
 *    badge says Pending — it is that the scheme's own member roll is unchanged while the file
 *    sits there, which only a round trip across two pages can show.
 * 2. **Refused rows are the output, not an error state.** A refused row is a borrower with no
 *    insurance whose lender may believe otherwise. Their reasons must be legible on arrival,
 *    without a click, and they must sort above the rows that passed.
 * 3. **The member reference is the deliverable.** The insurer mints it; the lender has never seen
 *    it and quotes it back on every later file. It reaches the roll on acceptance.
 * 4. **No employer-scheme furniture.** No grade column, no salary multiple, no benefit-basis
 *    picker — this product has none of them, and the roll's columns turn on the basis.
 *
 * The scheme itself is built over the API by `seedCreditLifeScheme`, because no console form
 * creates one; see that file for why that is honest rather than convenient, and why the uploader
 * is deliberately a different identity from the one this spec runs as.
 */

test.describe('staff credit-life scheme', () => {
  test('an uploaded schedule enrols nobody until a second person accepts it', async ({ page }) => {
    // A lender, a product, a published version, a scheme and a five-row file, then two console
    // pages and an acceptance. Not a 60-second test.
    test.slow();
    const { policyNumber, lenderName } = await seedCreditLifeScheme();
    const schemePage = `/staff/credit-life-schemes/${policyNumber}`;
    const rollPage = `/staff/group-schemes/${policyNumber}`;

    await page.goto(schemePage);
    await expect(page.getByRole('heading', { name: policyNumber })).toBeVisible({
      timeout: 20_000,
    });
    await expect(page.getByText(lenderName)).toBeVisible();

    /*
     * THE ROLL BEFORE ACCEPTANCE. One life: the opening borrower the scheme was issued with.
     * The five-row file is uploaded and judged and has put nobody on cover, and this is the
     * assertion that says so in the only terms that matter -- who is actually insured.
     */
    // The innermost div holding this Field's own <dt>, so the value read is this row's and not
    // the whole rail's text. `last()` because locators come back in DOM order and every ancestor
    // of the <dt> matches too.
    const livesOnCover = page
      .locator('div')
      .filter({ has: page.locator('dt', { hasText: /^Lives on cover$/ }) })
      .last()
      .locator('dd')
      .first();
    await expect(livesOnCover).toHaveText('1');

    // The file is there, and it says outright that nothing has happened.
    await expect(page.getByText('january-schedule.csv')).toBeVisible();
    await expect(page.getByText('Pending', { exact: true })).toBeVisible();
    await expect(page.getByText(/Nothing has happened yet/)).toBeVisible();
    await expect(page.getByText(/a second person must accept this file/)).toBeVisible();
    await expect(page.getByText(/awaiting a second person/)).toBeVisible();

    // "Would be", not "Enrolled". The count is an intention until somebody accepts.
    await expect(page.getByText('Would be enrolled')).toBeVisible();
    await expect(page.getByText('Refused')).toBeVisible();

    /*
     * THE REASONS ARE ON SCREEN, unclicked. The newest file opens by itself precisely so that the
     * most important thing on it is not behind a count somebody has to discover is clickable.
     */
    await expect(page.getByText(/borrower_full_name is blank/)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/disbursement_date 2027-12-01 is in the future/)).toBeVisible();
    await expect(page.getByText(/THIS BORROWER IS NOT COVERED/).first()).toBeVisible();

    /*
     * And they sort ABOVE the rows that passed. The first data row of the file's table is a
     * refused one -- line 4, the row with no name -- not line 2, which is fine. Reading a
     * several-hundred-row file top to bottom is how a person finds the ones to act on.
     */
    const firstRow = page.getByRole('table').last().locator('tbody tr').first();
    await expect(firstRow).toContainText('borrower_full_name is blank');

    // The report is offered per file, because it is the only place the lender ever learns the
    // references the insurer minted.
    await expect(page.getByRole('link', { name: 'Report for the lender' }).first()).toBeVisible();

    /*
     * NO EMPLOYER-SCHEME FURNITURE. A credit-life scheme has no grades and no salary multiple:
     * the amount insured comes off an amortisation schedule. Rendering either as an empty state
     * would be furniture, and the whole reason this page is not a branch inside GroupSchemePage.
     */
    await expect(page.getByText('Salary multiple')).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Grades' })).toHaveCount(0);

    /*
     * ACCEPTANCE, as a second person. The fixture uploaded as staff.admin; this browser is
     * staff.underwriter. The backend refuses a same-person acceptance, so a spec that did both
     * as one identity would fail here -- and if it ever passes, that control has stopped working.
     */
    await page.getByRole('button', { name: 'Accept' }).first().click();
    const confirmation = page.getByRole('group', { name: 'Accept this file?' });
    await expect(confirmation).toBeVisible();
    await expect(confirmation).toContainText('Refused rows are not');
    await confirmation.getByRole('button', { name: 'Accept' }).click();

    await expect(page.getByText('Accepted', { exact: true })).toBeVisible({ timeout: 20_000 });
    // Two different people handled it, said in words rather than as two uuids to diff by eye.
    await expect(page.getByText(/two people, as it should be/)).toBeVisible();
    // The intention became a fact: "Enrolled", not "Would be enrolled".
    await expect(page.getByText('Would be enrolled')).toHaveCount(0);
    // `first()`: every row that passed also carries an "Enrolled" outcome badge in the table
    // below, so the label is not unique on this page -- which is the point of it changing.
    await expect(page.getByText('Enrolled', { exact: true }).first()).toBeVisible();

    // Refused rows were NOT enrolled by the acceptance. Three of five passed.
    await expect(livesOnCover).toHaveText(String(1 + ENROLMENT_PASSING), { timeout: 20_000 });
    expect(ENROLMENT_PASSING + ENROLMENT_REFUSED).toBe(5);

    /*
     * THE MEMBER REFERENCE REACHES THE ROLL. This is the deliverable: the lender sent names and
     * loan amounts and got back a handle minted by the insurer, which they quote on every later
     * file and which an exits file names a departing borrower by.
     */
    await page.getByRole('link', { name: 'The member roll' }).click();
    await expect(page).toHaveURL(rollPage, { timeout: 15_000 });
    await expect(page.getByText('Juma Rajabu Kimaro')).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/^CL-[A-Z0-9]+-\d{6}$/).first()).toBeVisible();

    // The borrower refused for a future disbursement date is NOT on the roll. The row was
    // recorded and reported; the person is not insured.
    await expect(page.getByText('Hamisi Salum Ally')).toHaveCount(0);

    /*
     * And the roll drops the column that is structurally empty here. A grade is how an EMPLOYER
     * scheme decides what a life is worth; this product decides it from a loan. Rendering the
     * column anyway cost width on a screen read by scanning, for a column of em dashes.
     */
    await expect(page.getByRole('columnheader', { name: 'Reference' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Grade / salary' })).toHaveCount(0);
  });

  test('an exits file must be a CSV, and is refused before the network', async ({ page }) => {
    test.slow();
    const { policyNumber } = await seedCreditLifeScheme();
    await page.goto(`/staff/credit-life-schemes/${policyNumber}`);
    await expect(page.getByRole('heading', { name: policyNumber })).toBeVisible({
      timeout: 20_000,
    });

    // No exits file has ever been sent for this scheme, and the panel says so in the lender's
    // terms rather than showing an empty table.
    const exits = page
      .locator('section')
      .filter({ has: page.getByRole('heading', { name: 'Loans ending' }) });
    await expect(exits.getByText('No files yet')).toBeVisible();

    /*
     * XLSX is accepted for an enrolment schedule -- a lender exports one from a spreadsheet --
     * and refused for exits, which is a short list generated from a loan system. The asymmetry is
     * the backend's; the console mirrors it so a doomed upload fails at the file picker instead
     * of after a multipart round trip. That it fails WITHOUT a request is the assertion.
     */
    let requested = false;
    await page.route('**/credit-life-schemes/**/exits', (route) => {
      requested = true;
      return route.continue();
    });

    const chooser = page.waitForEvent('filechooser');
    await exits.getByRole('button', { name: 'Upload exits file' }).click();
    await (
      await chooser
    ).setFiles({
      name: 'exits.xlsx',
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: Buffer.from('not really a spreadsheet'),
    });

    await expect(exits.getByRole('alert')).toHaveText('An exits file must be a CSV');
    expect(requested, 'a client-refused file must not be sent').toBe(false);
  });
});
