import { expect, request as apiRequest, test, type Browser, type Page } from '@playwright/test';
import { staffToken } from './creditLife';
import { dmy, todayIso } from './dates';
import { asSeniorOnSameCase, decideAsSenior } from './underwriting';

/**
 * Underwriting domain e2e coverage against the real backend.
 *
 * `POST /underwriting/cases` is the only entry point onto this domain -- the
 * Underwriting queue (`GET /underwriting/cases`, covered separately in
 * staff-underwriting-queue.spec.ts) is now a real discovery path, but no
 * other domain's response ever re-surfaces a real case id (verified directly:
 * a real `GET /policies` response for the seeded policyholder below carries
 * no `underwritingCaseId` field at all, confirming the wire DTO, not just the
 * OpenAPI spec, drops it). So every test here still opens its own case and
 * works from the id its own response hands back, rather than depending on
 * the queue.
 *
 * "Amina Owner" (`d9937444-3873-4336-9cb7-addb486f3e1b`) is the same real
 * seeded policyholder used throughout staff-issue-policy.spec.ts.
 */

/**
 * A sum assured unique to THIS RUN, so the capture test can find the policy it caused among
 * the many Amina already carries. There is still no back-reference from a case to the policy
 * it produced, and the policy number is server-generated, so the amount is the only handle.
 *
 * Per-run rather than a fixed odd figure, which is what the first attempt used: this database
 * accumulates, so a constant stops being unique the moment the test runs twice — and it did,
 * failing on two matching rows left by an earlier attempt at this very test.
 *
 * Stays inside the seeded product's LOW sum-assured band (< 2,000,000) so the rating table
 * resolves and the engine recommends acceptance.
 */
const SUM_ASSURED_WHOLE = 1_500_000 + (Date.now() % 99_999);
const SUM_ASSURED = `${SUM_ASSURED_WHOLE}.00`;
const SUM_ASSURED_DISPLAY = `TZS ${SUM_ASSURED_WHOLE.toLocaleString('en-US')}.00`;

test.describe('staff underwriting', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/staff/underwriting/new');
    // 30s, not the 10s default: this is the first navigation of the test, so it
    // can land mid silent-SSO-renew, and the app shows "Signing in" while a
    // Keycloak round trip completes. The assertion was right; the budget was not.
    await expect(page.getByRole('heading', { name: 'Open an underwriting case' }))
      .toBeVisible({ timeout: 30_000 });
  });

  test('the real seeded product populates the picker', async ({ page }) => {
    const select = page.getByLabel('Product');
    await expect(select.locator('option', { hasText: 'Demo Term Life' })).toHaveCount(1);
  });

  /*
   * 'shows no matches for a nonsense applicant search' was removed. It is the same PartyPicker on
   * the same empty-result path as the one staff-issue-policy.spec.ts used to assert, and
   * PartyPicker.test.tsx already pins that component directly -- zero results, an unresolvable
   * pasted UUID, a failed search, and the race where a newer search must beat a slower older one.
   * Re-proving one of those per screen that mounts the picker is per-screen cost for
   * component-level behaviour.
   */
  /**
   * Assessing and deciding are two acts, and this is the test that says so.
   *
   * It used to read "decides it by submitting one assessment", with the comment
   * "decideIfPossible runs unconditionally on this one call -- submitting a single assessment
   * IS the decision". Both were accurate and both described the defect: a placeholder rules
   * engine settled every case on the platform and issued the policy, with nobody signing it.
   */
  test('an assessment recommends, an underwriter decides, and the decision survives a reload', async ({
    page,
  }) => {
    await openCaseForAmina(page);
    await expect(page.getByRole('heading', { name: 'Underwriting case' })).toBeVisible();
    await expect(page.getByText('None', { exact: true })).toBeVisible(); // referral status

    await page.getByLabel('Findings').fill('E2E test assessment, standard risk');
    await page.getByLabel('Risk score (optional)').fill('10');
    await page.getByRole('button', { name: 'Submit assessment' }).click();

    // Advice, and nothing more: no decision, and the case is still open for evidence.
    await expect(page.getByText(/The rules engine recommends/)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('heading', { name: 'Decision' })).not.toBeVisible();
    await expect(page.getByRole('button', { name: 'Submit assessment' })).toBeVisible();

    // Separation of duties. This identity opened the case and wrote its evidence, so the
    // decision is not theirs: no form, and a sentence saying who it belongs to.
    await expect(page.getByText(/another underwriter must decide it/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Record decision' })).not.toBeVisible();

    // A second underwriter decides. ACCEPT agrees with the recommendation, so no override.
    await decideAsSenior(page, 'Accept', 'Standard risk, in line with the recommendation');

    // Reload from scratch -- proves this is a real Postgres row, not the
    // store's in-memory state surviving a soft navigation.
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Decision', exact: true })).toBeVisible();
    // Both forms are gone -- a decided case takes no further evidence and no second decision.
    await expect(page.getByRole('button', { name: 'Submit assessment' })).not.toBeVisible();
    await expect(page.getByRole('button', { name: 'Record decision' })).not.toBeVisible();
  });

  /**
   * Separation of duties against the real backend, for the one person the console cannot
   * warn: a second tab, or a stale form. The server refuses the assessor too.
   */
  test('the underwriter who assessed a case is refused the decision by the server as well', async ({
    page,
  }) => {
    await openCaseForAmina(page);
    await page.getByLabel('Findings').fill('Standard risk');
    await page.getByLabel('Risk score (optional)').fill('10');
    await page.getByRole('button', { name: 'Submit assessment' }).click();
    await expect(page.getByText(/The rules engine recommends/)).toBeVisible({ timeout: 15_000 });

    // A real token for the same person the browser is signed in as -- see ./creditLife for
    // why it is minted from Keycloak and never fabricated.
    const caseId = page.url().split('/').pop() as string;
    const http = await apiRequest.newContext();
    try {
      const token = await staffToken(http, 'staff.underwriter');
      const response = await http.post(`http://localhost:8080/underwriting/cases/${caseId}/decision`, {
        headers: { Authorization: `Bearer ${token}` },
        data: { outcome: 'ACCEPT', reason: 'Deciding my own assessment' },
      });
      expect(response.status()).toBe(403);
      expect(((await response.json()) as { errorCode?: string }).errorCode).toBe(
        'UNDERWRITING_SEPARATION_OF_DUTIES',
      );
    } finally {
      await http.dispose();
    }
  });

  /**
   * The senior gate, against the real backend.
   *
   * `staff.underwriter` holds UNDERWRITER only. A decision that departs from the engine's
   * recommendation is refused to them -- by the form, and by the server behind it.
   */
  test('a junior underwriter cannot decide against the recommendation', async ({ page, browser }) => {
    // Opened and assessed by staff.senior, so the junior is free of separation of duties and
    // meets the senior gate alone -- otherwise the first refusal would hide the second.
    await page.goto(await caseOpenedAndAssessedBySenior(browser));
    await page.getByLabel('Decision').selectOption({ label: 'Decline' });

    await expect(page.getByText(/a senior underwriter has to record it/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Record decision' })).toBeDisabled();
    // The case is untouched: no decision, and the evidence form is still open.
    await expect(page.getByRole('heading', { name: 'Decision' })).not.toBeVisible();
  });

  /**
   * The whole capture chain, end to end against the real stack.
   *
   * Term, premium-paying term, payment frequency and beneficiary nominations lived only on
   * the manual issue form, which made it the only screen able to produce a complete policy:
   * one issued on the NORMAL path had no term, no maturity date -- it is derived from
   * commencement plus term -- and nobody nominated, because nobody had ever asked. That is
   * very likely why staff reached for manual issue in the first place.
   *
   * Accepted, not declined, so the decision actually issues a policy and the assertions land
   * on a real contract rather than on the case that produced it.
   */
  test('a proposal states the contract, and an accepted case issues it complete', async ({ page }) => {
    test.slow();
    await page.goto('/staff/underwriting/new');
    await page.getByRole('button', { name: 'Search for the applicant by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('Amina');
    await page.getByRole('option', { name: 'Amina Owner' }).click();
    await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    // Deliberately an odd figure. Amina carries many policies from other specs, and this is
    // what identifies the one THIS test caused -- the policy number is server-generated, and
    // there is still no back-reference from a case to the policy it produced.
    await page.getByLabel('Sum assured').fill(SUM_ASSURED);

    // A commencement date, because maturity is DERIVED from it plus the term: Policy.applyTerm
    // stores a term without one but leaves maturityDate null, and the detail page then reports
    // the risk-commences field as not recorded. A proposal states this in practice.
    await page.getByLabel('Proposed commencement date').fill(dmy(todayIso()));

    // `exact` matters: getByLabel matches case-insensitive SUBSTRINGS by default, so
    // "Term (months)" also matches "Premium-paying term (months)" and trips strict mode.
    await page.getByLabel('Term (months)', { exact: true }).fill('120');
    await page.getByLabel('Premium-paying term (months)').fill('60');
    await page.getByLabel('Premium frequency').selectOption('QUARTERLY');

    await page.getByRole('button', { name: 'Add beneficiary' }).click();
    await page.getByLabel('Beneficiary type').selectOption('FREEFORM');
    await page.getByLabel('Freeform designee').fill('The estate');
    await page.getByLabel('Share percent').fill('100');

    await page.getByRole('button', { name: 'Open case' }).click();
    await expect(page).toHaveURL(/\/staff\/underwriting\/[0-9a-f-]{36}$/, { timeout: 15_000 });

    await page.getByLabel('Findings').fill('Standard risk');
    await page.getByLabel('Risk score (optional)').fill('10');
    await page.getByRole('button', { name: 'Submit assessment' }).click();
    await expect(page.getByText(/The rules engine recommends/)).toBeVisible({ timeout: 15_000 });

    // A second underwriter decides -- this one opened and assessed it.
    await decideAsSenior(page, 'Accept', 'Standard risk, in line with the recommendation');
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Decision', exact: true })).toBeVisible({ timeout: 30_000 });

    // The applicant's own id, read off the case rather than written down -- party ids are
    // minted per seed run, and this file has already been bitten by hard-coded ones.
    const applicantHref = await page
      .locator('a[href^="/staff/parties/"]')
      .first()
      .getAttribute('href');
    const applicantId = (applicantHref as string).split('/').pop() as string;

    // The policy arrives through an AFTER_COMMIT listener, so it is not necessarily there the
    // instant the decision response lands. Identified by its SUM ASSURED, which is unique to
    // this test -- the policy number is server-generated and Amina has many other policies.
    await expect(async () => {
      await page.goto(`/staff/policies?policyholderPartyId=${applicantId}`);
      await expect(page.getByRole('row').filter({ hasText: SUM_ASSURED_DISPLAY })).toHaveCount(1);
    }).toPass({ timeout: 30_000 });

    // The frequency is already visible on the row itself, and it is the assertion this test
    // exists for: the listener divided by twelve unconditionally until the proposal could say
    // otherwise, so a quarterly payer would have been billed a monthly figure.
    const row = page.getByRole('row').filter({ hasText: SUM_ASSURED_DISPLAY });
    await expect(row).toContainText('/quarter');

    // The policy number is a button that opens the preview drawer, not a link.
    await row.getByRole('button').first().click();
    await page.getByRole('link', { name: /full detail/i }).click();
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });

    // The proposal's terms reached the contract. Every one of these was absent on an
    // automatically issued policy until the case could carry them.
    //
    // Asserted in the console's own words -- it humanises months into years, so a policy term
    // of 120 reads "10 years" and a premium-paying term of 60 reads "Premiums paid for 5
    // years." Asserting "120 months" would be asserting the wire, not the screen.
    await expect(page.getByText('10 years')).toBeVisible();
    await expect(page.getByText('Premiums paid for 5 years.')).toBeVisible();

    // Maturity is DERIVED from commencement plus term in Policy.applyTerm, so a real date here
    // is the proof the term actually landed rather than merely being echoed back. Ten years
    // past the commencement date the proposal asked for.
    await expect(page.getByText('Matures')).toBeVisible();

    // And the nomination taken on the proposal is a beneficiary on the contract -- one tab
    // along, since the record's registers each have their own now.
    await page.getByRole('tab', { name: 'Beneficiaries' }).click();
    await expect(page.getByText('The estate')).toBeVisible();
  });

  test('refers a case to a senior underwriter', async ({ page }) => {
    await openCaseForAmina(page);

    await page.getByRole('button', { name: 'Refer to senior underwriter' }).click();

    // referralStatus flips for real -- the button (only rendered while
    // referralStatus === 'NONE') disappears once it does.
    await expect(page.getByText('Referred to senior')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole('button', { name: 'Refer to senior underwriter' })).not.toBeVisible();
  });

  test('a staff.finance session (no UNDERWRITER role) sees no assessment form or refer button on a real case', async ({
    page,
    browser,
  }) => {
    await openCaseForAmina(page);
    const caseUrl = page.url();

    const financeContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const financePage = await financeContext.newPage();
    await financePage.goto(caseUrl);
    await expect(financePage.getByRole('heading', { name: 'Underwriting case' })).toBeVisible();
    await expect(financePage.getByRole('button', { name: 'Submit assessment' })).not.toBeVisible();
    await expect(financePage.getByRole('button', { name: 'Refer to senior underwriter' })).not.toBeVisible();
    // Declarations are the exception, and deliberately so: taking a proposal is not
    // underwriting it, so recording what the applicant said is open to any staff member
    // (and to agents), not just the role that decides the case.
    await expect(financePage.getByRole('heading', { name: 'Declarations' })).toBeVisible();
    await expect(financePage.getByRole('button', { name: 'Add a declaration' })).toBeVisible();
    await financeContext.close();
  });

  test('a second assessment racing against an already-decided case genuinely 409s, and does not resurface after a reload', async ({
    page,
    context,
  }) => {
    await openCaseForAmina(page);
    const caseUrl = page.url();

    // A second tab loads the SAME not-yet-decided case, capturing the
    // assessment form in memory before tab A decides it -- the real way this
    // 409 happens (two staff racing the same case), not a fabricated request.
    const page2 = await context.newPage();
    await page2.goto(caseUrl);
    await expect(page2.getByRole('button', { name: 'Submit assessment' })).toBeVisible();

    // The race is on the DECISION now, not the assessment. Two assessments on one case are
    // ordinary -- evidence accumulates -- so the 409 moved to where the conflict actually is:
    // two people settling the same case.
    // Tab A records the evidence and a second underwriter settles it on that -- tab A's own
    // user may not, having just assessed it.
    await page.getByLabel('Findings').fill('Tab A assesses first');
    await page.getByRole('button', { name: 'Submit assessment' }).click();
    await expect(page.getByText(/The rules engine recommends/)).toBeVisible({ timeout: 15_000 });
    await decideAsSenior(page, 'Accept', 'Settled while tab B still had the form open');

    await page2.getByLabel('Findings').fill('Tab B arrives too late');
    await page2.getByRole('button', { name: 'Submit assessment' }).click();
    await expect(page2.getByRole('alert')).toBeVisible({ timeout: 15_000 });

    // `submittingAssessment` is keyed by case id and outlives this form's own
    // mount/unmount -- reset-on-mount must clear it, or the 409 above would
    // resurface on the very next visit to this same case.
    await page2.reload();
    await expect(page2.getByRole('alert')).not.toBeVisible();
    await expect(page2.getByRole('heading', { name: 'Decision' })).toBeVisible();

    await page2.close();
  });

  test('a postponed case is not finished: further evidence re-decides it', async ({ page }) => {
    // POSTPONED is the engine's way of saying "come back with more evidence", and the UI used
    // to treat every decided case as final -- which made it the one outcome that could never
    // be resolved, on a real case, through the only screen that can assess one.
    await openCaseForAmina(page);

    await page.getByLabel('Findings').fill('Inconclusive -- awaiting specialist report');
    // >= 90 is SimpleRulesEngine's POSTPONE threshold, so POSTPONED agrees with the
    // recommendation and a junior underwriter may record it.
    await page.getByLabel('Risk score (optional)').fill('95');
    await page.getByRole('button', { name: 'Submit assessment' }).click();
    await expect(page.getByText(/The rules engine recommends/)).toBeVisible({ timeout: 15_000 });
    // Decided by a second underwriter; this one opened and assessed it.
    await asSeniorOnSameCase(page, async (senior) => {
      await senior.getByLabel('Decision').selectOption({ label: 'Postpone — more evidence needed' });
      await senior.getByLabel('Reason').fill('Awaiting the specialist report');
      await senior.getByRole('button', { name: 'Record decision' }).click();
      await senior.getByRole('button', { name: 'Postpone the case' }).click();
      await expect(senior.getByText('Postponed', { exact: true })).toBeVisible({ timeout: 15_000 });
    });

    await page.reload();
    await expect(page.getByText('Postponed', { exact: true })).toBeVisible({ timeout: 30_000 });
    // The form survives, and says what it is now for.
    await expect(page.getByRole('heading', { name: 'Submit further evidence' })).toBeVisible();
    await expect(page.getByText('Further evidence', { exact: true })).toBeVisible();

    // Reload first: a postponed case must still be resolvable on a cold load, not only while
    // the store happens to hold the response that postponed it.
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Submit further evidence' })).toBeVisible({
      timeout: 30_000,
    });

    await page.getByLabel('Findings').fill('Specialist report clear');
    await page.getByLabel('Risk score (optional)').fill('10');
    await page.getByRole('button', { name: 'Submit further evidence' }).click();

    // The recommendation moved on the new evidence -- and it weighed the LATEST assessment
    // per type, not the worst ever recorded, or the 95 above would recommend postponing
    // forever. Then a person acts on it.
    await expect(page.getByText(/The rules engine recommends/)).toBeVisible({ timeout: 15_000 });
    await decideAsSenior(page, 'Accept', 'Specialist report resolves it');

    await page.reload();
    await expect(page.getByText('Accept', { exact: true })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('heading', { name: 'Submit further evidence' })).not.toBeVisible();
  });

  test('records a declaration with the question as it was put, and a correction adds to the record rather than replacing it', async ({
    page,
  }) => {
    // underwriting.medical_disclosure existed from M4 with zero call sites, while claims
    // computes and shows requiresContestabilityReview -- a review with nothing to review.
    await openCaseForAmina(page);

    await expect(page.getByRole('heading', { name: 'Declarations' })).toBeVisible();
    await expect(page.getByText(/Nothing declared on this case/)).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'Add a declaration' }).click();
    await page.getByLabel('Question code').fill('Q1');
    await page.getByLabel('Question as asked').fill('Have you ever been treated for heart disease?');
    await page.getByLabel('Answer').fill('No');
    await page.getByRole('button', { name: /^Record 1 declaration/ }).click();

    await expect(page.getByText('Have you ever been treated for heart disease?')).toBeVisible({
      timeout: 15_000,
    });
    // One set on the record. Each is a named region, so "how many recordings are on this
    // case" is a real assertion rather than a text match that could catch anything.
    await expect(page.getByRole('region', { name: /^Declarations recorded/ })).toHaveCount(1);

    // A real Postgres row through a jsonb column, not the store's memory: the entity had
    // never been written before, so nothing had ever exercised its JSON binding.
    await page.reload();
    await expect(page.getByText('Have you ever been treated for heart disease?')).toBeVisible({
      timeout: 30_000,
    });

    // The correction. Both sets stay on the case -- later evidence supersedes earlier evidence
    // in the reader's judgement, never by deleting what the applicant originally said, which
    // is the only shape a non-disclosure argument can be made from.
    await page.getByRole('button', { name: 'Add a declaration' }).click();
    await page.getByLabel('Question code').fill('Q1');
    await page.getByLabel('Question as asked').fill('Have you ever been treated for heart disease?');
    await page.getByLabel('Answer').fill('Yes -- angioplasty 2021, omitted in error');
    await page.getByLabel('Notes (optional)').fill('Corrected after the specialist report arrived');
    await page.getByRole('button', { name: /^Record 1 declaration/ }).click();

    await expect(page.getByText('Yes -- angioplasty 2021, omitted in error')).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByRole('region', { name: /^Declarations recorded/ })).toHaveCount(2);
    // The original "No" is still there, in its own set, beside the correction.
    await expect(page.getByRole('region', { name: /^Declarations recorded/ }).first())
      .toContainText('No');
    await expect(page.getByText('Corrected after the specialist report arrived')).toBeVisible();
  });
});

/**
 * The one preamble every test that needs a real case shares: `POST /underwriting/cases` is the
 * only way onto this domain (see the file header), so each test opens its own.
 *
 * A real POST -> 201 -> navigation to the new case's own url. The case id is server-generated,
 * so the caller matches the url pattern and reads `page.url()` rather than any literal value.
 */
/** A case staff.senior opened and assessed (ACCEPT recommended), as a url, for another user. */
async function caseOpenedAndAssessedBySenior(browser: Browser): Promise<string> {
  const context = await browser.newContext({ storageState: 'e2e/.auth/staff-senior.json' });
  try {
    const seniorPage = await context.newPage();
    await seniorPage.goto('/staff/underwriting/new');
    await expect(seniorPage.getByRole('heading', { name: 'Open an underwriting case' })).toBeVisible({
      timeout: 30_000,
    });
    await openCaseForAmina(seniorPage);
    await seniorPage.getByLabel('Findings').fill('Standard risk');
    await seniorPage.getByLabel('Risk score (optional)').fill('10');
    await seniorPage.getByRole('button', { name: 'Submit assessment' }).click();
    await expect(seniorPage.getByText(/The rules engine recommends/)).toBeVisible({ timeout: 15_000 });
    return seniorPage.url();
  } finally {
    await context.close();
  }
}

async function openCaseForAmina(page: Page) {
  await page.getByRole('button', { name: 'Search for the applicant by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill('Amina');
  await page.getByRole('option', { name: 'Amina Owner' }).click();
  await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
  await expect(page.getByText('Resolving product version…')).not.toBeVisible();
  await page.getByLabel('Sum assured').fill('1500000.00');
  await page.getByRole('button', { name: 'Open case' }).click();
  await expect(page).toHaveURL(/\/staff\/underwriting\/[0-9a-f-]{36}$/, { timeout: 15_000 });
}
