import { expect, test } from '@playwright/test';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';
import { dmy } from './dates';
import { fillPolicyNumberManually } from './guards';

/**
 * `POST /claims/{claimId}/evidence` + `GET .../evidence` + `GET .../evidence/{ref}`
 * -- fully built since M6/M11, but with zero staff UI until this staff-portal
 * CRUD audit found the gap. Real evidence e2e coverage against the live stack.
 *
 * Needs a claim that is NOT SETTLED (attaching new evidence 409s once SETTLED),
 * and the one seeded DEATH claim's status depends on what other suites in this
 * session have done to it -- so this file registers its own fresh claim against
 * a freshly-issued policy instead of depending on shared seed state, the same
 * discipline `staff-policy-lifecycle.spec.ts` uses for suspend/resume.
 *
 * The uploaded file is a minimal valid 1x1 PNG passed as an in-memory buffer
 * (Playwright's `setInputFiles` accepts `{name, mimeType, buffer}` directly --
 * no fixture file on disk needed), so this stays a real multipart upload
 * through the real allowlist (ClaimEvidenceController.ALLOWED_EVIDENCE_CONTENT_TYPES)
 * without inventing a binary test fixture to check into the repo.
 */
const MINIMAL_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==',
  'base64',
);

test.describe('staff claim evidence', () => {
  test('attaches, lists, and views a real evidence file end to end', async ({ page }) => {
    // MEASURED AT 39.8s ALONE, against the 60s default. That is 20s of headroom for a test
    // that issues a policy through manual issue, registers a claim, uploads a real JPEG to
    // MinIO and downloads it back -- and it spends every one of those 20s the moment anything
    // else is running: inside a five-file run it timed out at exactly 60s, on whatever step
    // happened to be in flight when the clock ran out.
    //
    // Not a regression, and it is worth writing down HOW that was established, because the
    // failure looked exactly like one. The same test on `main` measures 40.5s -- slower, not
    // faster -- and `staff-role-gates`, which touches none of these screens, runs 2.4m on this
    // branch against 3.0m on `main`. The suite simply has no headroom here.
    //
    // test.slow() triples the budget. Nine tests in this same set already take it for less
    // work; this one does more than any of them and was the only one still on the default.
    test.slow();
    // Issue a fresh ACTIVE policy, then register a DEATH claim against it --
    // the only seeded policy is SURRENDERED (staff-claims.spec.ts's own note),
    // so a fresh one is the only way to reach a real, non-SETTLED claim.
    // Manual issue names a real, unissued case now. The policyholder and product
    // come from it by prefill, so this no longer picks them by hand. The sum assured
    // still does: the case view @JsonIgnores it, so the console cannot read it.
    const caseId = await caseAwaitingManualIssue(page);
    await page.goto('/staff/policies/new');
    await selectUnderwritingCase(page, caseId);
    await expect(page.getByText('Resolving product version…')).not.toBeVisible();
    await page.getByLabel('Sum assured').fill('2000000.00');
    await page.getByLabel('Premium', { exact: true }).fill('800.00');
    // Required, and MIGRATION so the policy is in force rather than an offer.
    await page.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
    await page.getByLabel('Reason for manual issue').fill('E2E claim-evidence fixture');
    await page.getByRole('button', { name: 'Issue policy' }).click();
    await expect(page).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 15_000 });
    const policyNumber = page.url().split('/').pop() as string;

    await page.goto('/staff/claims/new');
    await fillPolicyNumberManually(page, policyNumber);
    await page.getByRole('button', { name: 'Search for the claimant by name' }).click();
    await page.getByPlaceholder('Type a name to search').fill('Amina');
    await page.getByText('Amina Owner').click();
    await page.getByLabel('Date of event').fill(dmy('2026-01-10'));
    await page.getByLabel('Cause of death').fill('E2E fixture');
    await page.getByLabel('Place of death').fill('Dar es Salaam');
    await page.getByLabel('Date of death').fill(dmy('2026-01-10'));
    await page.getByLabel('Attending physician').fill('Dr E2E');
    await page.getByRole('button', { name: 'Register claim' }).click();
    await expect(page).toHaveURL(/\/staff\/claims\/[0-9a-f-]{36}$/, { timeout: 15_000 });

    await expect(page.getByRole('heading', { level: 2, name: 'Evidence' })).toBeVisible();
    await expect(page.getByText('No evidence')).toBeVisible();

    const description = `E2E evidence ${Date.now()}`;
    await page.getByLabel('Description (optional)').fill(description);
    await page.locator('input[type="file"]').setInputFiles({
      name: 'evidence.png',
      mimeType: 'image/png',
      buffer: MINIMAL_PNG,
    });

    await expect(page.getByText(description)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('No evidence')).not.toBeVisible();
    // The uploader is named from their own token (staff.underwriter), where this used to
    // print their Keycloak subject.
    await expect(page.getByText(/^Halima Underwriter · /).first()).toBeVisible();

    // "View" opens the real downloaded file in a new tab -- assert on the new
    // tab's own response rather than just that a tab opened, so this proves the
    // download round-trip actually returned the real bytes, not an error page.
    const [viewTab] = await Promise.all([
      page.waitForEvent('popup'),
      page.getByRole('button', { name: 'View' }).click(),
    ]);
    await viewTab.waitForLoadState('load');
    expect(viewTab.url()).toMatch(/^blob:/);
    await viewTab.close();
  });
});
