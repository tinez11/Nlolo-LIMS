import { expect, test, type Page } from '@playwright/test';
import { issueRealPolicy } from './policies';

/**
 * The first mutation's e2e coverage: real Keycloak session, real PUT against the
 * real backend, real Postgres row change -- confirmed by reloading the page after
 * a save and re-reading the persisted result, not just trusting the in-memory
 * store update.
 *
 * SELF-CONTAINED BECAUSE IT OWNS ITS POLICY. Each test issues its own and mutates it
 * freely.
 *
 * It used to run against the shared seeded policy `POL-6BD5702F`, and carried a careful note
 * about clearing beneficiaries afterward so "another spec, or a person testing by hand" would
 * find the same starting point. That discipline was right for a shared record and is now
 * unnecessary -- and the constant itself had become a lie: policy numbers are minted
 * `POL-<random>` per issuance, so that one stopped existing when the volumes were last reset
 * and can never be recreated. It returns zero rows. All five tests in this file were failing
 * for a reason with nothing to do with beneficiaries, and no re-run would ever have fixed it.
 */

async function openEdit(page: Page) {
  await page.getByRole('button', { name: 'Edit beneficiaries' }).click();
}

async function removeAllRows(page: Page) {
  const removeButton = page.getByRole('button', { name: 'Remove beneficiary' });
  while ((await removeButton.count()) > 0) {
    await removeButton.first().click();
  }
}

async function addFreeformRow(page: Page, designee: string, sharePercent: number) {
  await page.getByRole('button', { name: 'Add beneficiary' }).click();
  // A fresh row defaults to type PARTY (blankBeneficiaryRow()); switch it before
  // the freeform input exists at all, since it is conditionally rendered on type.
  await page.getByRole('combobox').selectOption('FREEFORM');
  await page.getByPlaceholder('Designee, e.g. "My Estate"').fill(designee);
  await page.getByRole('spinbutton').fill(String(sharePercent));
}

async function addPartyRow(page: Page, nameQuery: string, resultText: string, sharePercent: number) {
  await page.getByRole('button', { name: 'Add beneficiary' }).click();
  await page.getByRole('button', { name: 'Search for the beneficiary by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill(nameQuery);
  await selectPickerOption(page, resultText);
  await page.getByRole('spinbutton').fill(String(sharePercent));
}

/**
 * Pick a party from the open PartyPicker, scoped to the picker's own listbox.
 *
 * The scoping is load-bearing HERE specifically, and the reason is worth keeping.
 * A bare `getByText('Amina Owner')` resolves against the whole page, and this is
 * the one picker that lives on the POLICY DETAIL page -- which renders the
 * policyholder's name too, as a link to their party page. The link exists on
 * first paint; the search results arrive a request later. So the unscoped
 * locator matched the policyholder link, clicked it, and navigated away to
 * `/staff/parties/<id>` before the dropdown had rendered anything at all. The
 * visible symptom was a timeout three lines later on a missing spinbutton,
 * which points nowhere near the real cause.
 *
 * Roughly twenty other specs still use the unscoped form and pass, because
 * their picker sits on a create form where the party's name appears nowhere
 * else on the page. That is luck, not correctness: the day any of those screens
 * starts showing a party name, they break exactly like this one did.
 */
async function selectPickerOption(page: Page, resultText: string) {
  await page.getByRole('option', { name: resultText }).click();
}

/** The Beneficiaries panel on the policy detail page, for scoping assertions to it. */
function beneficiariesPanel(page: Page) {
  return page
    .locator('section')
    .filter({ has: page.getByRole('heading', { name: 'Beneficiaries' }) });
}

test.describe('staff beneficiaries edit', () => {
  test.beforeEach(async ({ page }) => {
    // Its own freshly issued policy, not the shared seeded one. See the note above
    // POLICY_PATH's removal: the literal POL-6BD5702F stopped existing when the volumes were
    // last reset and can never be recreated, so all five tests here were failing for a reason
    // unrelated to beneficiaries. Owning the policy is also strictly better for this file in
    // particular, which clears and rewrites the designation -- it no longer has to promise to
    // put a shared record back the way it found it.
    test.slow();
    const policyNumber = await issueRealPolicy(page, 'E2E beneficiaries fixture');
    await page.goto(`/staff/policies/${policyNumber}`);
    await expect(page.getByRole('heading', { name: 'Beneficiaries' })).toBeVisible();
  });

  test('rejects shares that do not sum to 100 before ever reaching the network', async ({ page }) => {
    // The zod schema mirrors PolicyApiImpl.validateAndBuildBeneficiaries's own
    // sum-to-100 rule exactly (by design, so the client rejects what the server
    // would before a round trip) -- which means this specific rule can NEVER
    // reach a real 422 through this UI: the PUT is never sent at all. Verified
    // by listening for the request rather than assuming; an earlier version of
    // this test claimed to confirm "the real backend 422" here and was wrong --
    // it was reading the CLIENT schema's identically-worded rejection message.
    let putFired = false;
    page.on('request', (req) => {
      if (req.method() === 'PUT' && req.url().includes('/beneficiaries')) putFired = true;
    });

    await openEdit(page);
    await removeAllRows(page);
    await addFreeformRow(page, 'E2E Underweighted Estate', 50);

    await page.getByRole('button', { name: 'Save' }).click();

    // A whole-array zod issue (path: ['beneficiaries']), rendered via
    // errors.beneficiaries.root.message -- not the saving-resource's server-error
    // banner, which never activates because saveBeneficiaries is never called.
    await expect(page.getByText(/must sum to 100, got 50/i)).toBeVisible();
    expect(putFired).toBe(false);

    // The form must still be open with the rejected input on screen -- a failed
    // save silently closing would discard what the user typed.
    await expect(page.getByRole('button', { name: 'Save' })).toBeVisible();
    await expect(page.getByPlaceholder('Designee, e.g. "My Estate"')).toHaveValue(
      'E2E Underweighted Estate',
    );

    // Nothing was ever sent, so cancelling here needs no server-side cleanup.
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByRole('button', { name: 'Edit beneficiaries' })).toBeVisible();
  });

  test('rejects a row with both a party id and a freeform designee set', async ({ page }) => {
    await openEdit(page);
    await removeAllRows(page);
    await page.getByRole('button', { name: 'Add beneficiary' }).click();
    // Leave type at its default PARTY, fill the party id field, THEN switch to
    // FREEFORM without clearing it -- the exact "both set" shape the backend's
    // hasParty == hasFreeform check rejects independent of the declared type.
    await page.getByRole('button', { name: 'Search for the beneficiary by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('Amina');
    await selectPickerOption(page, 'Amina Owner');
    await page.getByRole('combobox').selectOption('FREEFORM');
    await page.getByPlaceholder('Designee, e.g. "My Estate"').fill('Also this');
    await page.getByRole('spinbutton').fill('100');

    // Caught client-side by the same zod rule the backend enforces -- this never
    // reaches the network at all, unlike the sum-to-100 case above.
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText(/exactly one of a party or a freeform designee/i)).toBeVisible();

    await page.getByRole('button', { name: 'Cancel' }).click();
  });

  test('saves a real beneficiary, persists it across a reload, then clears it back to empty', async ({
    page,
  }) => {
    await openEdit(page);
    await removeAllRows(page);
    await addFreeformRow(page, 'E2E Beneficiary', 100);

    await page.getByRole('button', { name: 'Save' }).click();

    // The PUT returns no body -- success means the form closed AND the read view
    // now shows data that only a refetch could have produced.
    await expect(page.getByRole('button', { name: 'Edit beneficiaries' })).toBeVisible();
    await expect(page.getByText('E2E Beneficiary')).toBeVisible();
    await expect(page.getByText('100% share')).toBeVisible();

    // Reload from scratch -- proves the row is a real Postgres row, not just the
    // store's in-memory state surviving a soft navigation.
    await page.reload();
    await expect(page.getByText('E2E Beneficiary')).toBeVisible();
    await expect(page.getByText('100% share')).toBeVisible();

    // Restore the shared fixture to the seeded baseline (empty) for whatever runs
    // next -- another spec, a repeat run, or a person testing by hand.
    await openEdit(page);
    await removeAllRows(page);
    await page.getByRole('button', { name: 'Save' }).click();

    await expect(page.getByRole('button', { name: 'Edit beneficiaries' })).toBeVisible();
    await expect(page.getByText('None recorded.')).toBeVisible();

    await page.reload();
    await expect(page.getByText('None recorded.')).toBeVisible();
  });

  test('the read view resolves a real party beneficiary to its name, not a raw uuid', async ({
    page,
  }) => {
    await openEdit(page);
    await removeAllRows(page);
    await addPartyRow(page, 'Amina', 'Amina Owner', 100);

    await page.getByRole('button', { name: 'Save' }).click();

    await expect(page.getByRole('button', { name: 'Edit beneficiaries' })).toBeVisible();
    // The whole point: PartyName resolves the id to a real name, not
    // `d9937444-3873-4336-9cb7-addb486f3e1b` showing up raw in the read view.
    //
    // Scoped to the Beneficiaries panel, and it has to be. This fixture's
    // beneficiary IS the policyholder, and the Policyholder field on this same
    // page now resolves to a name too -- so a page-wide `getByText('Amina
    // Owner')` matches twice and fails strict mode. Worse, it would pass while
    // proving nothing: the policyholder's name alone would satisfy it even if
    // the beneficiary still rendered a raw uuid, which is the exact regression
    // this test exists to catch.
    await expect(beneficiariesPanel(page).getByText('Amina Owner')).toBeVisible();

    await page.reload();
    await expect(beneficiariesPanel(page).getByText('Amina Owner')).toBeVisible();

    // Restore the shared fixture to the seeded baseline (empty).
    await openEdit(page);
    await removeAllRows(page);
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('None recorded.')).toBeVisible();
  });

  // A resource keyed by policy number persists in the store across the edit
  // form's own mount/unmount. If a failed save's error state were not reset when
  // the form is reopened, an old rejection would resurface immediately on a
  // completely fresh attempt, before the user has done anything wrong this time.
  //
  // Every rule this form's own zod schema enforces is mirrored exactly from the
  // backend, so a REAL server rejection is unreachable through the UI -- the
  // client always wins first (see the sum-to-100 test above). Proving this
  // resilience path genuinely needs a server-side failure, so this intercepts
  // the one PUT request the app itself fires and answers it with a fabricated
  // 500 -- the app does not know the difference; it runs its real error-handling
  // code (lib/http's interceptor, toApiError, the saving-resource banner) against
  // a real HTTP response, same as any other failure. Auth is untouched: the
  // logged-in session and every other request stay completely real.
  test('does not resurface a stale error banner when reopening after a failed save', async ({
    page,
  }) => {
    await page.route('**/policies/*/beneficiaries', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: 'Server error',
          status: 500,
          detail: 'Simulated failure for this test',
          errorCode: 'SIMULATED',
          traceId: 'e2e-simulated-trace',
        }),
      }),
    );

    await openEdit(page);
    await removeAllRows(page);
    await addFreeformRow(page, 'E2E Simulated Failure', 100); // passes every client rule
    await page.getByRole('button', { name: 'Save' }).click();

    await expect(page.getByRole('alert')).toContainText('Simulated failure for this test');
    await page.getByRole('button', { name: 'Cancel' }).click();

    await page.unroute('**/policies/*/beneficiaries');
    await openEdit(page);
    await expect(page.getByRole('alert')).not.toBeVisible();

    await page.getByRole('button', { name: 'Cancel' }).click();
  });
});
