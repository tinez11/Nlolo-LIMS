import { expect, test } from '@playwright/test';
import { expectStaffShellReady } from './guards';
import { issueRealPolicy } from './policies';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';

/**
 * What the platform says to customers, through the real stack.
 *
 * The chain under test runs the whole way down: the console posts a manual issue, `policy`
 * publishes an event, `communication`'s AFTER_COMMIT listener renders a seeded Swahili template
 * and posts it to the mock SMS aggregator, and a `notification_dispatch` row comes back up
 * through a second HTTP endpoint into this page. Nothing here is stubbed at the browser.
 */
test.describe('staff communications', () => {
  test.use({ storageState: 'e2e/.auth/staff.json' });

  test('the Communications section is reachable and lists the seeded templates', async ({ page }) => {
    await page.goto('/staff/policies');
    await expectStaffShellReady(page);

    // Through the sidebar, not by URL: half of what a new section is for is being findable.
    await page.getByRole('link', { name: 'Message templates' }).click();
    await expect(page).toHaveURL(/\/staff\/notifications\/templates$/);
    await expect(page.getByRole('heading', { name: 'Message templates' })).toBeVisible({ timeout: 30_000 });

    // The four messages of the offer lifecycle. Grouped by key, so each appears once as a panel
    // heading regardless of how many channel/language variants sit under it.
    for (const message of ['Offer made', 'Offer closing', 'Cover started', 'Offer expired']) {
      await expect(page.getByRole('heading', { name: message })).toBeVisible();
    }

    // The placeholders an editor must not delete. Rendered as chips precisely so somebody
    // rewriting the wording can see which tokens are real before they remove one.
    await expect(page.getByText('{{expiryDate}}').first()).toBeVisible();
  });

  test('the outbox is reachable and honest about what it does not know', async ({ page }) => {
    await page.goto('/staff/notifications/messages');
    await expectStaffShellReady(page);
    await expect(page.getByRole('heading', { name: 'Messages sent' })).toBeVisible({ timeout: 30_000 });

    // Either a populated outbox or an honest empty state -- never a spinner that never resolves.
    // Same idiom the other list screens use, and it keeps this independent of whether anything
    // happens to have been sent in this tenant today.
    const table = page.getByRole('table', { name: 'Messages sent to customers' });
    const empty = page.getByText('Nothing sent yet');
    await expect(table.or(empty)).toBeVisible({ timeout: 20_000 });

    // The status filter round-trips through the URL, like every other list here.
    //
    // By ROLE, not by text. "FAILED" is both a filter chip and a status badge, so a text match is
    // unique only while the outbox happens to be empty — which it was when this was written, and
    // is not now that the adapter correctly suppresses sends and records each one. The chip is a
    // button; the badges are spans.
    await page.getByRole('button', { name: 'FAILED' }).click();
    await expect(page).toHaveURL(/status=FAILED/);
  });

  /**
   * The one that proves the chain rather than the screens.
   *
   * An offer is issued through the console and the customer is told about it — by a listener,
   * over a real HTTP call to the aggregator, recorded in a real row. The panel on the policy page
   * is where the desk actually reads this: standing on an offer, "has this person been told?" is
   * the immediate follow-up to "not yet on cover".
   */
  test('issuing an offer tells the customer, and the policy page shows it', async ({ page }) => {
    test.slow();
    const caseId = await caseAwaitingManualIssue(page);
    await page.goto('/staff/policies/new');
    await selectUnderwritingCase(page, caseId);
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('800.00');
    // UNDERWRITING_OVERRIDE, so the policy stays an OFFER: that is the case where the customer
    // is told to pay by a date. A MIGRATION would already be in force and get a different
    // message entirely.
    await page.getByLabel('Why is this being issued by hand?').selectOption('UNDERWRITING_OVERRIDE');
    await page.getByLabel('Reason for manual issue').fill('E2E communications fixture');
    await page.getByRole('button', { name: 'Issue policy' }).click();
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });

    // The panel, on the page somebody would actually be looking at -- reached by clicking
    // its tab, which is how they would get there.
    await page.getByRole('tab', { name: 'Messages' }).click();
    await expect(page.getByRole('heading', { name: 'Messages' })).toBeVisible({ timeout: 20_000 });

    // TWO rows, not one, and asserting the count is the point rather than a workaround for a
    // strict-mode violation: the seeded policyholder has a phone AND an email, and email is
    // additional rather than alternative -- an address is a second chance at telling somebody
    // their cover has not started, not a reason to skip the channel they actually read. Two rows
    // here is that decision, proven through the real stack.
    // Exactly two: one per channel. The count IS the assertion about multi-channel delivery --
    // there are only two channels, and the panel renders one row per dispatch.
    await expect(page.getByText('Offer made')).toHaveCount(2, { timeout: 20_000 });
  });

  test('a policy in force shows the customer was told their cover started', async ({ page }) => {
    test.slow();
    // issueRealPolicy issues on a MIGRATION basis, so the policy is in force on arrival and both
    // PolicyIssued and PolicyActivated fire together.
    const policyNumber = await issueRealPolicy(page, 'E2E communications in-force fixture');

    await page.goto(`/staff/policies/${policyNumber}?tab=messages`);
    await expect(page.getByRole('heading', { name: 'Messages' })).toBeVisible({ timeout: 20_000 });
    // Two again -- SMS and email -- for the same reason as the test above.
    await expect(page.getByText('Cover started')).toHaveCount(2, { timeout: 20_000 });
    // And NOT told to pay by a date to start cover they already have. This is the assertion that
    // would have caught the message being a lie: a MIGRATION publishes PolicyIssued and
    // PolicyActivated together, so without the status on the payload this customer would have
    // received both "you are covered" and "pay by 9 October to start your cover".
    await expect(page.getByText('Offer made')).toHaveCount(0);
  });
});
