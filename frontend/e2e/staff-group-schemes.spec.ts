import { expect, test } from '@playwright/test';
import { issueRealPolicy } from './policies';
import { dmy, todayIso } from './dates';
import { asAdmin } from './admin';
import { fillPolicyNumberManually } from './guards';
import { acceptProposedScheme, createGroupProduct, pickParty } from './groupSchemes';

/**
 * Group business, end to end against the real stack.
 *
 * The two things worth proving here cannot be proved anywhere cheaper:
 *
 * 1. **The scheme's total is derived and stays derived.** The form sends no sum
 *    assured; the server computes it from the opening schedule, and the master
 *    policy, the scheme page and the client record all agree. One number on
 *    several surfaces — a unit test can pin any one of them and only a real round
 *    trip pins that they match.
 * 2. **The free cover limit reaches the screen.** A member over the limit is
 *    covered up to it and flagged for evidence, and the console says so both in
 *    the live preview while typing and in the row afterwards.
 * 3. **A scheme comes through the pipeline.** The form PROPOSES: it opens an
 *    underwriting case, an underwriter decides it with no engine recommendation
 *    to depart from, and the decision issues the scheme as an offer.
 *
 * No GROUP_LIFE product is seeded, so each run authors one. That is the same
 * shape `staff-distribution.spec.ts` already uses, and it keeps this suite from
 * depending on seed data that nobody has committed to keeping.
 */

const FLAT_BENEFIT = '5000000.00';

test.describe('staff group schemes', () => {
  test('proposes a scheme, has it underwritten, and derives its total from its members', async ({
    page,
    browser,
  }) => {
    // Authoring a product as admin, proposing, assessing, deciding and then walking four
    // surfaces does not fit 60 seconds any more -- the pipeline added two round trips and a
    // second console page to every group fixture.
    test.slow();
    const productLabel = await asAdmin(browser, createGroupProduct);

    await page.goto('/staff/group-schemes/new');
    await expect(page.getByRole('heading', { name: 'Propose a group scheme' })).toBeVisible();

    await pickParty(page, 'Search for the employer by name', 'Amina', 'Amina Owner');
    await page.getByLabel('Product').selectOption({ label: productLabel });

    await page.getByLabel('Benefit per member').fill(FLAT_BENEFIT);

    // Two lives on the opening schedule. The form starts with exactly one row,
    // because a scheme cannot be issued empty.
    await pickParty(page, 'Search employees by name', 'Amina', 'Amina Owner');
    await page.getByRole('button', { name: 'Add member' }).click();
    await pickParty(page, 'Search employees by name', 'Baraka', 'Baraka Other', 'last');

    await page.getByLabel('Premium', { exact: true }).fill('1200000.00');

    // The running total is this page's preview of what the server will derive:
    // two lives at 5,000,000 each.
    await expect(page.getByText('TZS 10,000,000.00')).toBeVisible();

    await page.getByRole('button', { name: 'Propose scheme' }).click();

    // Lands on the CASE, not on a scheme: there is no scheme yet, and there will not be one
    // until an underwriter decides there should be. That is the whole change.
    await expect(page).toHaveURL(/\/staff\/underwriting\/[0-9a-f-]{36}$/, { timeout: 20_000 });
    const caseUrl = page.url();

    /*
     * And it is in the QUEUE, tagged. A group case carries no life assured -- the schedule
     * is the life -- so without the tag it reads as an individual case whose applicant is a
     * company, which is exactly the confusion the tag exists to prevent.
     *
     * The row is found by this case's own proposal number, not by position: the queue holds
     * two dozen open cases, and `first()` would assert the tag on somebody else's work.
     */
    const proposalNumber = (
      await page.getByText(/^PRO-[0-9A-F]{8}$/).first().textContent()
    )?.trim();
    expect(proposalNumber).toMatch(/^PRO-[0-9A-F]{8}$/);

    await page.goto('/staff/underwriting');
    const queueRow = page.getByRole('row').filter({ hasText: proposalNumber! });
    await expect(queueRow.getByText('Group scheme')).toBeVisible({ timeout: 20_000 });

    await page.goto(caseUrl);
    const policyNumber = await acceptProposedScheme(page);
    const schemeUrl = `/staff/group-schemes/${policyNumber}`;

    // Issued as an OFFER: the employer accepts by paying, exactly as an individual does.
    await page.goto(`/staff/policies/${policyNumber}`);
    await expect(page.getByText('Proposed', { exact: true })).toBeVisible();
    await page.goto(schemeUrl);

    await expect(page.getByRole('heading', { name: policyNumber })).toBeVisible();
    // Derived server-side, and equal to the preview above.
    await expect(page.getByText('TZS 10,000,000.00').first()).toBeVisible();
    await expect(page.getByRole('table')).toBeVisible();

    // The master policy carries the same total -- the service restates it in the
    // same transaction, and this is the only check that the two agree in a real
    // database rather than in one test's fixture.
    await page.goto(`/staff/policies/${policyNumber}`);
    await expect(page.getByText('TZS 10,000,000.00').first()).toBeVisible();
    // And the policy page offers the way back across, only because this policy
    // is a scheme.
    await expect(page.getByRole('link', { name: 'Member schedule' })).toBeVisible();

    /*
     * THE JOINER IS NOT ASSERTED HERE ANY MORE, and the reason is a real gap rather than a
     * shortcut. addMember requires the scheme IN FORCE, a scheme is now an offer until the
     * employer's first premium clears, and this console has no action that accepts an offer
     * — for group or individual business. e2e/policies.ts already works around the same wall
     * for individual policies by issuing on a MIGRATION basis, "so the policy is in force on
     * arrival".
     *
     * So the browser cannot reach an in-force scheme through the flow a person would use.
     * GroupSchemeIntegrationTest asserts the joiner and the restated total against a real
     * database, which is where that behaviour was always proven; what only a browser can
     * prove — that the form proposes, the queue shows it, the decision issues it, and the
     * totals agree across three surfaces — is asserted above and below.
     */
    await page.goto(schemeUrl);

    /*
     * Searching the roll by member name. Only two lives are on this schedule -- a real one
     * holds hundreds, and "is this person covered" is not a question anyone answers by
     * paging.
     *
     * What the assertions pin is that the filter is SERVER-side: the row that must be
     * ABSENT is the proof. A pass over the fetched page would search only the twenty-five
     * rows in hand and report "not covered" for somebody who is.
     */
    await page.getByLabel('Search members by name').fill('Baraka');
    await page.getByLabel('Search members by name').press('Enter');
    await expect(page).toHaveURL(/[?&]q=Baraka/, { timeout: 10_000 });
    await expect(page.getByText('Baraka Other')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('cell', { name: 'Amina Owner' })).toHaveCount(0);

    // A name nobody on this scheme has is an empty SEARCH, not an empty scheme -- the
    // roll still has its members and the copy must not claim otherwise.
    await page.getByLabel('Search members by name').fill('NobodyHereIsCalledThis12345');
    await page.getByLabel('Search members by name').press('Enter');
    await expect(page.getByText(/No member matching/)).toBeVisible({ timeout: 15_000 });
    await page.getByRole('button', { name: 'Show all' }).click();
    await expect(page.getByText('Baraka Other')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('cell', { name: 'Amina Owner' }).first()).toBeVisible();

    /*
     * The client-record route to the same schedule, which is what the Clients area's
     * second half exists to serve: open the policyholder, see the scheme, see who is
     * covered under it, and reach the roll in ONE hop.
     *
     * Before this panel the way through was Clients -> the client -> Policies -> the
     * GRP row -> the policy page -> Member schedule: four hops, and each of them
     * required already knowing that "members" live under a contract rather than under
     * the company.
     *
     * Asserted here rather than in a spec of its own because this is where a scheme
     * with a known policyholder and a known member count actually exists. A test that
     * went looking for "some client holding some scheme" would pass vacuously on a
     * tenant that had none.
     */
    await page.goto(`/staff/policies/${policyNumber}`);
    const policyholderLink = page.getByRole('link', { name: 'Amina Owner' }).first();
    await expect(policyholderLink).toBeVisible({ timeout: 15_000 });
    await policyholderLink.click();
    await expect(page).toHaveURL(/\/staff\/parties\/[0-9a-f-]{36}$/, { timeout: 15_000 });

    const schemesPanel = page
      .locator('section')
      .filter({ has: page.getByRole('heading', { name: 'Group schemes' }) });
    await expect(schemesPanel).toBeVisible({ timeout: 20_000 });

    // The scheme this run just issued, listed on the client's own record.
    await expect(schemesPanel.getByText(policyNumber)).toBeVisible({ timeout: 20_000 });

    // Its members, inline. This client holds more than one scheme by now, so the
    // inline preview is not asserted -- what must hold is that the roll is one click
    // away and that the link goes to the SCHEDULE, not to the policy record.
    const schemeLink = schemesPanel.getByRole('link', { name: new RegExp(policyNumber) });
    await schemeLink.click();
    await expect(page).toHaveURL(`/staff/group-schemes/${policyNumber}`, { timeout: 15_000 });
    await expect(page.getByRole('heading', { name: policyNumber })).toBeVisible();
    await expect(page.getByText('TZS 10,000,000.00').first()).toBeVisible({ timeout: 15_000 });
  });

  test('a member above the free cover limit is covered up to it and flagged', async ({ page, browser }) => {
    test.slow();
    const productLabel = await asAdmin(browser, createGroupProduct);

    await page.goto('/staff/group-schemes/new');
    await pickParty(page, 'Search for the employer by name', 'Amina', 'Amina Owner');
    await page.getByLabel('Product').selectOption({ label: productLabel });

    // 4x salary against a 100,000,000 limit. A member on 30,000,000 is worth
    // 120,000,000 and is therefore 20,000,000 over it.
    await page.getByLabel('Basis').selectOption('SALARY_MULTIPLE');
    await page.getByLabel('Multiple of annual salary').fill('4');
    await page.getByLabel('Free cover limit (optional)').fill('100000000.00');

    await pickParty(page, 'Search employees by name', 'Amina', 'Amina Owner');
    // exact: true -- "Multiple of annual salary" also contains "Annual salary",
    // and a substring label match resolves to both fields.
    await page.getByLabel('Annual salary', { exact: true }).fill('30000000.00');

    // The live preview says so BEFORE anything is submitted -- which is the whole
    // reason the preview exists: finding out after saving is finding out too late
    // to ask the member about it.
    await expect(page.getByText(/needs medical evidence/)).toBeVisible();
    await expect(page.getByText('1 member over the free cover limit.')).toBeVisible();

    await page.getByLabel('Premium', { exact: true }).fill('900000.00');
    await page.getByRole('button', { name: 'Propose scheme' }).click();

    // Through the pipeline: the free cover limit is a term of the PROPOSAL, so it has to
    // survive the case and the decision to reach the issued schedule.
    await page.goto(`/staff/group-schemes/${await acceptProposedScheme(page)}`);

    // Covered for the limit, not for the full benefit and not for nothing.
    await expect(page.getByText('TZS 100,000,000.00').first()).toBeVisible();
    await expect(page.getByText('of TZS 120,000,000.00')).toBeVisible();
    await expect(page.getByText('Evidence required')).toBeVisible();
    // Counted on the summary, so nobody has to find them by eye down the schedule.
    await expect(page.getByText('over the free cover limit')).toBeVisible();
  });

  /*
   * 'refuses a scheme with an unpriced member, before reaching the network' was removed here. It
   * built a whole group product through an admin context to reach a form it then never submitted:
   * the rule it checked is the salary-per-member one, pinned in groupSchemeIssueForm.test.ts
   * ('requires a salary per member on a salary-multiple scheme'), and the only other thing it
   * showed was that a client-rejected form sends nothing -- a property of every form on this
   * console, now pinned once in BeneficiariesPanel.test.tsx rather than re-proved per feature at
   * real-stack prices.
   */
  test('a claim on a scheme must name the life that died', async ({ page, browser }) => {
    // The console half of the group-claims fix. A claim registered against a scheme used to
    // record only a policy number and a claimant -- and the claimant is who is FILING, the
    // widow, not who died. On a 500-life schedule that left nothing on the claim saying which
    // employee it was, and registration valued it against the scheme's whole 10,000,000 total
    // rather than the member's own 5,000,000.
    //
    // The backend behaviour is proven against a real database and a real payment rail in
    // GroupClaimIntegrationTest and ClaimSettlementEndToEndTest. What only a browser can prove
    // is the wiring: that the field appears for a scheme, is refused empty, and is absent on
    // individual business.
    test.slow();
    const productLabel = await asAdmin(browser, createGroupProduct);

    await page.goto('/staff/group-schemes/new');
    await pickParty(page, 'Search for the employer by name', 'Amina', 'Amina Owner');
    await page.getByLabel('Product').selectOption({ label: productLabel });
    await page.getByLabel('Benefit per member').fill(FLAT_BENEFIT);
    await pickParty(page, 'Search employees by name', 'Amina', 'Amina Owner');
    await page.getByRole('button', { name: 'Add member' }).click();
    await pickParty(page, 'Search employees by name', 'Baraka', 'Baraka Other', 'last');
    await page.getByLabel('Premium', { exact: true }).fill('1200000.00');
    await page.getByRole('button', { name: 'Propose scheme' }).click();
    const policyNumber = await acceptProposedScheme(page);

    await page.goto('/staff/claims/new');
    // CLAIMANT FIRST, POLICY SECOND, and the order is load-bearing here in a way it is not on
    // an individual claim. Once a scheme's number is entered, this page renders a member
    // <option> per life -- and those lives include Amina Owner, who is also the claimant. An
    // <option> answers to getByText but is never "visible" to Playwright, so the claimant
    // picker's own text locator resolves to it and the click hangs until the test times out.
    // Choosing the claimant before the member list exists sidesteps it, and matches the order
    // the form itself asks in.
    await page.getByRole('button', { name: 'Search for the claimant by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('Amina');
    await page.getByText('Amina Owner').click();
    await fillPolicyNumberManually(page, policyNumber);

    // The field exists only because this policy is a scheme.
    await expect(page.getByLabel('Who died')).toBeVisible({ timeout: 15_000 });

    // TODAY, not a fixed past date. The scheme was created moments ago, so its members are
    // covered from today -- and the server checks cover AS AT THE DATE OF EVENT, refusing
    // "was not covered on 2026-08-01 (covered from ...)". A hardcoded date made this test fail
    // on a rule that was working correctly, which is the right rule and the wrong fixture.
    const eventDate = todayIso();
    await page.getByLabel('Date of event').fill(dmy(eventDate));
    await page.getByLabel('Claim type').selectOption('DEATH');
    await page.getByLabel('Cause of death').fill('Natural causes');
    await page.getByLabel('Place of death').fill('Dar es Salaam');
    await page.getByLabel('Date of death').fill(dmy(eventDate));
    await page.getByLabel('Attending physician').fill('Dr. E2E Test');

    // Submitted with no member named: refused here, before the network.
    await page.getByRole('button', { name: 'Register claim' }).click();
    await expect(page.getByText('Choose which member this claim is for')).toBeVisible();
    await expect(page).not.toHaveURL(/\/staff\/claims\/[0-9a-f-]{36}$/);

    // The picker is populated from THIS scheme's schedule, so naming a member clears the
    // client-side refusal.
    await page.getByLabel('Who died').selectOption({ index: 1 });
    await expect(page.getByText('Choose which member this claim is for')).toHaveCount(0);

    /*
     * REGISTRATION ITSELF IS NOT ASSERTED HERE, for the same reason the joiner is not
     * asserted in the first test: the server refuses a claim on a scheme that is not in
     * force ("Policy GRP-... was not in force on ..."), a scheme is an offer until the
     * employer's first premium clears, and this console has no action that accepts an
     * offer. GroupClaimIntegrationTest registers, approves and settles a member claim
     * against a real database, and ClaimSettlementEndToEndTest carries one to a real
     * payment rail; both value it at the member's own benefit rather than the scheme's
     * total, which was the defect this work fixed.
     */
  });

  test('a claim on an individual policy asks for no member', async ({ page }) => {
    // The mirror: the field is a property of contracts that have a schedule, and its absence
    // here is what keeps it from being asked for on every claim on the platform.
    test.slow();
    const policyNumber = await issueRealPolicy(page, 'E2E group-claims individual fixture');
    await page.goto('/staff/claims/new');
    await fillPolicyNumberManually(page, policyNumber);
    await expect(page.getByLabel('Who died')).toHaveCount(0);
  });

  test('an individual policy offers no member schedule', async ({ page }) => {
    // Straight to an individual policy rather than clicking the list: a policy number on
    // /staff/policies is a row-activation BUTTON that opens the drawer, not a link, so there
    // is nothing there matching /^POL-/ to click. The cross-link is rendered only for a
    // GROUP_LIFE contract, so its absence on this page is the assertion.
    //
    // Issued here rather than the literal POL-6BD5702F, which was "the seeded individual
    // policy" until the volumes were last reset. Policy numbers are minted POL-<random>, so
    // that one can never exist again and this test was failing on a missing fixture rather
    // than on anything to do with member schedules.
    test.slow();
    const policyNumber = await issueRealPolicy(page, 'E2E group-schemes individual-policy fixture');
    await page.goto(`/staff/policies/${policyNumber}`);
    await expect(page.getByRole('heading', { name: policyNumber })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Member schedule' })).toHaveCount(0);
  });
});
