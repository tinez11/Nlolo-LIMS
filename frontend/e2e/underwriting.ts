import { expect, type Page } from '@playwright/test';

/**
 * Fixtures for the underwriting flow, shared because manual issuance now needs one.
 *
 * `POST /policies/manual-issue` requires a real underwriting case and refuses one that
 * already has a policy. Every spec that issues a policy therefore has to produce a case
 * first — which is not incidental friction, it is the change: the console used to invent a
 * `crypto.randomUUID()` per submission, so the suite could conjure policies out of nothing
 * because the product could.
 */

/** Opens a case for the seeded Amina Owner against the seeded Demo Term Life product. */
/**
 * The product a case is opened against, when the default term product is not the point.
 *
 * `Nlolo Money-Back 20` is the seeded endowment that pays WHILE THE LIFE ASSURED LIVES, which is
 * the only seeded product that produces a payout schedule at all — a spec about payouts has to
 * open its case against it or there is nothing to review.
 */
export const DEMO_TERM_PRODUCT = 'Demo Term Life (DEMO-TERM-01)';
export const MONEY_BACK_PRODUCT = 'Nlolo Money-Back 20 (END-MB-20)';

export async function openCaseForAmina(
  page: Page,
  sumAssured = '1500000.00',
  productLabel: string = DEMO_TERM_PRODUCT,
): Promise<string> {
  await page.goto('/staff/underwriting/new');
  await page.getByRole('button', { name: 'Search for the applicant by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill('Amina');
  await page.getByRole('option', { name: 'Amina Owner' }).click();
  await page.getByLabel('Product').selectOption({ label: productLabel });
  await expect(page.getByText('Resolving product version…')).not.toBeVisible();
  await page.getByLabel('Sum assured').fill(sumAssured);
  await page.getByRole('button', { name: 'Open case' }).click();
  await expect(page).toHaveURL(/\/staff\/underwriting\/[0-9a-f-]{36}$/, { timeout: 15_000 });
  return page.url().split('/').pop() as string;
}

/** Records one assessment against the case currently on screen. */
export async function assess(page: Page, findings: string, riskScore: string): Promise<void> {
  await page.getByLabel('Findings').fill(findings);
  await page.getByLabel('Risk score (optional)').fill(riskScore);
  await page.getByRole('button', { name: 'Submit assessment' }).click();
  // The recommendation panel is the acknowledgement that the evidence landed. Waiting on it
  // rather than on a toast keeps this honest: it is read back from the case, not the form.
  await expect(page.getByText(/The rules engine recommends/)).toBeVisible({ timeout: 15_000 });
}

/**
 * Records the decision on the case currently on screen.
 *
 * Two clicks, because the decision is confirmed: a decided case is closed to further evidence
 * unless it was POSTPONED, and an acceptance issues the policy. The confirming button carries
 * the outcome's own verb rather than repeating "Record decision", so this maps the outcome to
 * it instead of clicking the same name twice.
 */
export async function decide(page: Page, outcomeLabel: string, reason: string): Promise<void> {
  await page.getByLabel('Decision').selectOption({ label: outcomeLabel });
  await page.getByLabel('Reason').fill(reason);
  await page.getByRole('button', { name: 'Record decision' }).click();
  await page.getByRole('button', { name: confirmLabelFor(outcomeLabel) }).click();
  await expect(page.getByRole('heading', { name: 'Decision', exact: true })).toBeVisible({ timeout: 15_000 });
}

/** The confirming button's label for an outcome, mirroring `DecisionPanel`. */
export function confirmLabelFor(outcomeLabel: string): string {
  if (outcomeLabel.startsWith('Decline')) return 'Decline the risk';
  if (outcomeLabel.startsWith('Postpone')) return 'Postpone the case';
  return 'Record the acceptance';
}

/**
 * Runs `act` as `staff.senior` on the case the given page is showing.
 *
 * Separation of duties: the underwriter who opened or assessed a case may not decide it, so a
 * spec driving the case as `staff.underwriter` hands the decision to a second person. Its own
 * browser context, like `asAdmin` in ./admin, so the calling page keeps its identity.
 */
export async function asSeniorOnSameCase(
  page: Page,
  act: (seniorPage: Page) => Promise<void>,
): Promise<void> {
  const browser = page.context().browser();
  if (!browser) throw new Error('asSeniorOnSameCase needs a browser-backed context');
  const context = await browser.newContext({ storageState: 'e2e/.auth/staff-senior.json' });
  try {
    const seniorPage = await context.newPage();
    await seniorPage.goto(page.url());
    await expect(seniorPage.getByRole('heading', { name: 'Underwriting case' })).toBeVisible({
      timeout: 30_000,
    });
    await act(seniorPage);
  } finally {
    await context.close();
  }
}

/** The ordinary two-person path: someone other than the assessor records the decision. */
export async function decideAsSenior(page: Page, outcomeLabel: string, reason: string): Promise<void> {
  await asSeniorOnSameCase(page, (seniorPage) => decide(seniorPage, outcomeLabel, reason));
}

/**
 * A decided case with NO policy against it, ready to be issued by hand.
 *
 * Decided DECLINED on purpose, and the reason is the whole point of the manual path. An
 * ACCEPT publishes `UnderwritingDecisionMade`, which the policy module consumes and issues
 * from — so an accepted case is spent the moment it is decided, and manual-issuing against it
 * is refused with a 409 naming the policy that already exists.
 *
 * A DECLINED case issues nothing automatically, which leaves exactly the situation
 * `openapi-policy.yaml` gives as manual issue's reason for existing: "re-issuing after a
 * manual review overturns an automated block". So this fixture is not a workaround for the
 * new constraint; it is the flow the endpoint was documented for.
 *
 * A risk score of 80 puts `SimpleRulesEngine` past its decline threshold, so DECLINED AGREES
 * with the recommendation and no senior underwriter is needed to record it.
 *
 * ## Why it runs in its own context
 *
 * Assessing and deciding are both `hasRole('UNDERWRITER')`, and most specs that issue a
 * policy are not underwriters — billing and finaccounting run as a finance officer, claims
 * adjudication as an assessor. Driving the fixture on the calling page made every one of
 * them hang: the decision panel does not render without the role, so the test waited out its
 * timeout on a control that was never going to appear.
 *
 * Same shape and same reasoning as `asAdmin` in ./admin: the fixture belongs to whoever is
 * allowed to create it, and the assertions stay with the identity under test. The browser
 * comes off the calling page rather than being threaded through every helper signature.
 */
export async function caseAwaitingManualIssue(
  page: Page,
  sumAssured = '1500000.00',
  productLabel: string = DEMO_TERM_PRODUCT,
): Promise<string> {
  const browser = page.context().browser();
  if (!browser) throw new Error('caseAwaitingManualIssue needs a browser-backed context');

  const underwriterContext = await browser.newContext({ storageState: 'e2e/.auth/staff.json' });
  try {
    const uwPage = await underwriterContext.newPage();
    const caseId = await openCaseForAmina(uwPage, sumAssured, productLabel);
    await assess(uwPage, 'E2E fixture: adverse findings', '80');
    // A second underwriter decides: the one who opened and assessed it may not.
    await decideAsSenior(uwPage, 'Decline', 'E2E fixture: declined, to be overturned by manual issue');
    return caseId;
  } finally {
    await underwriterContext.close();
  }
}

/**
 * Fills the manual-issue form's underwriting case field.
 *
 * A native select rather than a typeahead, because `GET /underwriting/cases` has no
 * free-text search to build one over. Options read `PRO-XXXXXXXX — DECLINED`, so the case is
 * matched on its id via the option value rather than on that label.
 */
export async function selectUnderwritingCase(page: Page, caseId: string): Promise<void> {
  await page.getByLabel('Underwriting case this policy is issued from').selectOption(caseId);
}
