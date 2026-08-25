import { expect, test, type Page } from '@playwright/test';

/**
 * The first mutation's e2e coverage: real Keycloak session, real PUT against the
 * real backend, real Postgres row change -- confirmed by reloading the page after
 * a save and re-reading the persisted result, not just trusting the in-memory
 * store update.
 *
 * SELF-CONTAINED AND IDEMPOTENT ON PURPOSE. This runs against the shared seeded
 * policy POL-6BD5702F, which other specs and manual testers also use. Every test
 * here clears whatever beneficiaries already exist (form-local, no request sent)
 * before setting up its own known state, and the suite ends with the policy back
 * at zero beneficiaries -- the seeded baseline -- so a repeated run, another spec,
 * or a person testing by hand afterward all see the same starting point.
 */

const POLICY_PATH = '/staff/policies/POL-6BD5702F';

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

test.describe('staff beneficiaries edit', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(POLICY_PATH);
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
    await page.getByText('Amina Owner').click();
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
