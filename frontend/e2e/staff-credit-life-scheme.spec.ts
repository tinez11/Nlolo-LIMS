import { expect, request as apiRequest, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import {
  ENROLMENT_PASSING,
  ENROLMENT_REFUSED,
  seedCreditLifeFixtures,
  seedCreditLifeScheme,
  staffToken,
} from './creditLife';
import { dmy } from './dates';

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
    // The page names its lender -- in the header, and again in the Commission panel, where the
    // lender is who earns. First is the header.
    await expect(page.getByText(lenderName).first()).toBeVisible();

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
    /*
     * AND THE LIMIT CAN BE CORRECTED. It is a number typed once, and a wrong one insures every
     * borrower for a fraction of their loan and opens an underwriting case for each -- which is
     * exactly what happened on the first scheme somebody set up this way. Until this control
     * existed the only remedy was a second scheme.
     */
    await expect(page.getByRole('button', { name: 'Change the free cover limit' })).toBeVisible();

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

    /*
     * THE REPORT DOWNLOADS, and this asserts the bytes rather than the affordance. It was an
     * anchor pointing straight at the endpoint, which cannot carry this SPA's in-memory token:
     * the request went out anonymous, the dev server answered the unknown path with index.html,
     * and the browser saved the console's own HTML under the name the person expected. A visible
     * control proved nothing about that; a saved file's first line does.
     */
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Report for the lender' }).first().click();
    const saved = await download;
    expect(saved.suggestedFilename()).toMatch(/^enrolment-report-.*\.csv$/);
    const reportText = readFileSync(await saved.path(), 'utf8');
    expect(reportText, 'the report must be CSV, not the console').not.toContain('<!doctype html');
    // member_reference leads, because it is the only place the lender ever learns them.
    expect(reportText.split('\n')[0]).toContain('member_reference');

    // The columns are explained where somebody can read them out to a lender.
    await page.getByRole('button', { name: 'What goes in it' }).first().click();
    await expect(page.getByText(/cover starts on it/).first()).toBeVisible();
    // This scheme HAS a borrower, so the guide promises the example that really is in the file.
    // Its mirror — a scheme with nobody on it — is asserted in the set-up test below.
    await expect(page.getByText(/carries one of this scheme/)).toBeVisible();

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
    /*
     * AND THE ROLL SAYS WHICH FILE EACH BORROWER CAME IN ON. The question that produced this
     * column was somebody counting: "three names, and I uploaded two." The third had been typed
     * at set-up and nothing distinguished it. The operational form of the same question outlives
     * that fix -- in a dispute a lender asks which file you covered somebody on.
     */
    await expect(page.getByRole('columnheader', { name: 'Came in on' })).toBeVisible();
    // The opening borrower this scheme was issued with did not arrive on a file, and the cell
    // says so rather than showing an em dash that reads as missing data.
    await expect(page.getByText('Opening schedule').first()).toBeVisible();
    await expect(page.getByText('january-schedule.csv').first()).toBeVisible();

    /*
     * AND THE WAY BACK. The roll used to be a one-way door: this page linked to it, it linked
     * only to the policy record, and its one primary action was "Add member" -- a form that
     * cannot work on this product, since it collects a grade and a salary while a credit-life
     * member is a loan. Somebody who came here to look a borrower up and then wanted to send the
     * next file found no upload and nothing saying where the upload was.
     */
    await expect(page.getByRole('button', { name: 'Add member' })).toHaveCount(0);
    await page.getByRole('link', { name: 'Upload a file' }).click();
    await expect(page).toHaveURL(schemePage, { timeout: 15_000 });
    await expect(page.getByRole('button', { name: 'Upload schedule' })).toBeVisible();
  });

  test('a credit-life scheme can be set up from the console at all', async ({ page }) => {
    /*
     * Until this page existed the answer was no. The group-scheme form offers three bases and
     * none of them is AMORTISING_LOAN, so every credit-life scheme on this platform — including
     * the fixtures in this very file — was created with an API call. A product could be authored,
     * a lender registered, enrolment files parsed and judged, and the one act that starts the
     * whole thing could only be done by somebody with curl.
     */
    test.slow();
    const http = await apiRequest.newContext();
    let fixtures;
    try {
      fixtures = await seedCreditLifeFixtures(http, await staffToken(http, 'staff.admin'));
    } finally {
      await http.dispose();
    }

    await page.goto('/staff/credit-life-schemes/new');
    await expect(page.getByRole('heading', { name: 'Set up a credit-life scheme' })).toBeVisible();

    // The lender, by name, through the same picker every other party field on this console uses.
    await page.getByRole('button', { name: 'Search for the lender by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('E2E Microfinance');
    await page.getByRole('option', { name: fixtures.lenderName }).click();

    // Only CREDIT_LIFE products are offered. A term-life product here would be a 409 after the
    // form, which is the version of this that wastes somebody's afternoon.
    await page.getByLabel('Product').selectOption({ label: fixtures.productLabel });

    await page.getByLabel('Premium rate (% of each loan)').fill('0.5');
    await page.getByLabel('Free cover limit (optional)').fill('600000000.00');
    await page.getByLabel('Premium recorded on the contract').fill('52000.00');
    // Before the opening borrower's loan. A book being onboarded always has loans older than
    // today, and a member cannot join a scheme that did not exist yet -- the form asks for this
    // rather than letting the server refuse the whole submission over a date it never showed.
    await page.getByLabel('Risk commences').fill(dmy('2026-06-01'));

    // MIGRATION is the default and it is load-bearing: an offer cannot receive an enrolment file
    // until its first premium clears and nothing in this console accepts an offer, so a scheme
    // created as one would be unusable. The form says so; this proves the default is the usable
    // one rather than trusting the sentence.
    await expect(page.getByLabel('Why this scheme is in force')).toHaveValue('MIGRATION');

    /*
     * NO BORROWER IS TYPED, and its absence is the assertion. The form used to demand one
     * because the service demanded a non-empty schedule -- a rule written for employer schemes,
     * where the schedule IS the contract. Here it made somebody type a life they then met again
     * on the member roll without recognising them, which is how "the roll has three names and I
     * uploaded two" happens.
     */
    await expect(page.getByLabel("Borrower's full name")).toHaveCount(0);
    await expect(page.getByLabel('Amount borrowed')).toHaveCount(0);

    await page.getByRole('button', { name: 'Set up the scheme' }).click();

    /*
     * Lands on the MONTHLY FILES, not on the policy record. The policy record answers "one
     * contract, one life, total X" — true, and not what somebody who has just onboarded a lender
     * is about to do. The next act is always the first enrolment file.
     */
    await expect(page).toHaveURL(/\/staff\/credit-life-schemes\/GRP-[A-Z0-9]+$/, {
      timeout: 30_000,
    });
    await expect(page.getByText(fixtures.lenderName)).toBeVisible({ timeout: 20_000 });
    // In force on arrival, and therefore able to take a file — which is the whole point of the
    // issuance-basis default above.
    await expect(page.getByText('Active', { exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Borrowers joining' })).toBeVisible();

    // One life on cover: the opening borrower, and nobody the form invented.
    const livesOnCover = page
      .locator('div')
      .filter({ has: page.locator('dt', { hasText: /^Lives on cover$/ }) })
      .last()
      .locator('dd')
      .first();
    // NOBODY is on cover: the agreement exists, the book has not arrived. It used to read 1,
    // because the form made somebody type a borrower -- a life they then met again on the
    // member roll without recognising them.
    await expect(livesOnCover).toHaveText('0');

    /*
     * AND THE TEMPLATE COMES BACK WITH THAT BORROWER IN IT.
     *
     * This is the reconciliation the whole scheme-scoped template exists for. Somebody has just
     * typed one borrower into the set-up form; the file they are about to send the lender hands
     * that same borrower back as a filled row, so "what does a date look like, where does the
     * amount go, what is a member reference" is answered by their own entry rather than by a
     * paragraph beside the download.
     *
     * The reference is the interesting half: the lender has never seen one, the insurer minted it
     * at issuance, and this is the first place it is ever shown to them.
     */
    /*
     * THE SPREADSHEET IS WHAT THE BUTTON GIVES, and that is not cosmetic. A CSV template cannot
     * survive Excel: it rewrites dates on open and again on save, so two real lender files came
     * back with every date mangled -- the second AFTER being told the format, because the worked
     * example had been rewritten too. A date in a spreadsheet is a typed cell and round-trips.
     */
    const xlsxDownload = page.waitForEvent('download');
    await page.getByRole('button', { name: /Schedule template to send the lender/ }).click();
    const xlsx = await xlsxDownload;
    expect(xlsx.suggestedFilename()).toMatch(/^enrolment-template-GRP-[A-Z0-9]+\.xlsx$/);
    // A real workbook, not an error page rendered as one: every xlsx is a zip.
    expect(readFileSync(await xlsx.path()).subarray(0, 2).toString('latin1')).toBe('PK');

    // The CSV stays, second and quieter, for a lender whose loan system exports one. Its contents
    // are readable as text, so this is where the worked example is asserted.
    const templateDownload = page.waitForEvent('download');
    // Scoped to the enrolment panel: BOTH panels offer a CSV alternative now that the exits file
    // takes a workbook too, so the bare name resolves to two buttons.
    const joining = page
      .locator('section')
      .filter({ has: page.getByRole('heading', { name: 'Borrowers joining' }) });
    await joining.getByRole('button', { name: 'or as a CSV' }).click();
    const template = await templateDownload;
    expect(template.suggestedFilename()).toMatch(/^enrolment-template-GRP-[A-Z0-9]+\.csv$/);
    const templateText = readFileSync(await template.path(), 'utf8');
    const [header] = templateText.trim().split('\n');
    expect(header).toContain('borrower_full_name');
    expect(header).toContain('disbursement_date');

    /*
     * THE HEADER ALONE, on a scheme with nobody on it yet. The worked example is one of the
     * lender's OWN borrowers, so a brand-new scheme has none to show -- and that is honest rather
     * than a gap: the format still survives Excel, because the file this button gives is a
     * spreadsheet whose dates are typed cells. The example appears from the second month, once
     * the first file has been accepted, which is asserted on the seeded scheme above.
     */
    expect(templateText.trim().split('\n')).toHaveLength(1);

    /*
     * AND THE GUIDE SAYS SO. It promised a worked example unconditionally, which was true while
     * every scheme was forced to open with a typed borrower and became a lie the day they
     * stopped -- sending somebody to look for a row that is not in the file.
     */
    await page.getByRole('button', { name: 'What goes in it' }).first().click();
    await expect(page.getByText(/the column headings alone/)).toBeVisible();
    await expect(page.getByText(/carries one of this scheme/)).toHaveCount(0);
  });

  test('a file that is neither CSV nor XLSX is refused before the network', async ({ page }) => {
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
     * BOTH kinds take a CSV or a workbook now. Exits was CSV-only on the argument that it is a
     * short list from a loan system, which missed that the file carries a DATE and that a CSV
     * cannot survive Excel. What is still refused is a format neither side can read, and the
     * console refuses it at the file picker so a doomed upload never becomes a multipart round
     * trip. That it fails WITHOUT a request is the assertion.
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
      name: 'exits.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('not really a spreadsheet'),
    });

    await expect(exits.getByRole('alert')).toHaveText('An exits file must be a CSV or an XLSX');
    expect(requested, 'a client-refused file must not be sent').toBe(false);
  });
});
