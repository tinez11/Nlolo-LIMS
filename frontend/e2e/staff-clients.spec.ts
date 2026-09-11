import { expect, test, type Page } from '@playwright/test';
import { dmy } from './dates';
import { expectStaffShellReady } from './guards';

/**
 * A register's total, which lives in the page header as "N individual clients · in
 * this tenant" rather than in a stat card. Anchored at the start so the noun is the
 * whole assertion: `individual clients` matches only the unfiltered line and
 * `pending individual clients` only the filtered one -- with a bare substring the two
 * would both match the filtered line and the comparison below would be comparing a
 * number against itself.
 *
 * The noun also carries the AREA, which is what makes these assertions prove the
 * split: a count reading "individual clients" cannot be the whole register's total.
 */
function countLine(page: Page, label: string) {
  return page.getByText(new RegExp(String.raw`^[\d,]+ ${label} · `));
}

/** A real PNG, because the upload endpoint checks the content type. Same bytes as the KYC suite. */
const MINIMAL_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==',
  'base64',
);

/**
 * The client register, and one client's whole record.
 *
 * This file was `staff-kyc-review.spec.ts` and covered a KYC work queue. The screen
 * became a register, and then became TWO: individuals at
 * `/staff/clients/individuals` and companies/groups at
 * `/staff/clients/organisations`, split on a real server-side `partyType` filter
 * because a natural person and an organisation are reviewed by different people
 * against different evidence. Each shows everyone of its kind by default rather than
 * only those awaiting KYC, each is searchable by name, and each carries its own
 * pending badge. The queue did not disappear -- so the KYC coverage below is kept
 * rather than replaced.
 *
 * `GET /parties` was the endpoint that made any of this possible: before it, a party
 * PENDING KYC with nothing referencing it (a fresh self-service or agent-assisted
 * registration) was invisible to staff, with no way to find it at all.
 */
test.describe('staff clients register', () => {
  test('shows every client by default, not only those awaiting KYC', async ({ page }) => {
    await page.goto('/staff/clients/individuals');
    await expectStaffShellReady(page);
    await expect(page.getByRole('heading', { name: 'Individual clients' })).toBeVisible({ timeout: 30_000 });

    // The substance of the rename. A bare visit carries no filter, so a register
    // called Clients cannot be showing only the few dozen awaiting a KYC decision.
    await expect(page).not.toHaveURL(/kycStatus=/);
    // The count reads "N clients · in this tenant" unfiltered and "N pending clients ·
    // matching this filter" once a filter is on, so the unqualified noun is itself the
    // assertion that nothing is filtering.
    await expect(countLine(page, 'individual clients')).toBeVisible();

    const table = page.getByRole('table', { name: 'Individual clients' });
    await expect(table).toBeVisible({ timeout: 15_000 });

    // The count on the unfiltered register must EXCEED the pending count. Asserting a
    // VERIFIED row is on screen would have been the obvious check and a worse one: it
    // depends on where verified clients happen to fall in a newest-first first page,
    // and in this tenant recent registrations are all pending, so it failed while the
    // screen was behaving correctly. Comparing the two totals tests the actual claim
    // -- that the default filter is not PENDING -- whatever the data looks like.
    // Each count is read after its OWN page load, not by clicking a filter and
    // reading again. The count deliberately keeps showing the previous total
    // while the next list is in flight -- so clicking Pending and reading
    // immediately returns the ALL count under the Pending label, which is how this
    // assertion first "proved" that 67 was greater than 67.
    const statTotal = async (label: string) => {
      const text = (await countLine(page, label).textContent()) ?? '';
      return Number(text.match(/([\d,]+)/)?.[1]?.replace(/,/g, '') ?? '0');
    };

    const totalAll = await statTotal('individual clients');

    await page.goto('/staff/clients/individuals?kycStatus=PENDING');
    await expect(page.getByRole('table', { name: 'Individual clients' })).toBeVisible({ timeout: 15_000 });
    const totalPending = await statTotal('pending individual clients');

    expect(totalPending).toBeGreaterThan(0);
    expect(totalAll).toBeGreaterThan(totalPending);
  });

  test('finds a freshly agent-registered PENDING client, and the KYC filter still round-trips', async ({
    page,
    browser,
  }) => {
    const agentContext = await browser.newContext({ storageState: 'e2e/.auth/agent.json' });
    const agentPage = await agentContext.newPage();

    const fullName = `E2E Clients Fixture ${Date.now()}`;
    await agentPage.goto('/agents/customers/new');
    await agentPage.getByLabel('Full name').fill(fullName);
    await agentPage.getByLabel('Date of birth').fill(dmy('1990-05-12'));
    await agentPage.getByRole('button', { name: 'Register individual' }).click();
    await expect(agentPage.getByText('Registered', { exact: true })).toBeVisible({ timeout: 15_000 });
    const partyIdText = await agentPage.getByText(/^[0-9a-f]{8}-[0-9a-f]{4}-/).textContent();
    const partyId = (partyIdText ?? '').trim();
    await agentContext.close();

    // Searchable by name. A register of every client the tenant has ever registered
    // is not browsable, so this is how anyone actually finds a person -- and it is
    // also what makes this assertion independent of how many clients exist.
    await page.goto('/staff/clients/individuals');
    await expectStaffShellReady(page);
    await page.getByLabel('Search by name').fill(fullName);
    await page.getByLabel('Search by name').press('Enter');
    await expect(page).toHaveURL(/[?&]q=/, { timeout: 10_000 });
    await expect(page.getByText(fullName)).toBeVisible({ timeout: 15_000 });

    // The KYC filter still narrows, and still round-trips through the URL.
    await page.getByText('Pending', { exact: true }).first().click();
    await expect(page).toHaveURL(/kycStatus=PENDING/);
    await expect(page.getByText(fullName)).toBeVisible({ timeout: 15_000 });

    await page.getByText(fullName).click();
    await expect(page).toHaveURL(`/staff/parties/${partyId}`);
    await expect(page.getByRole('heading', { level: 2, name: 'KYC verification' })).toBeVisible();
  });

  /**
   * Correcting a client, and the one rule that governs it: KYC SURVIVES.
   *
   * <p>KYC here is what a passport is — a document verifying that this person is who they say
   * they are — so amending the record does not un-verify it. Only a real round trip can prove
   * that: the aggregate, the endpoint and the page each have their own opportunity to decide to
   * be clever and reset a corrected client to PENDING, and a unit test pins any one of them.
   *
   * <p>Driven from VERIFIED rather than asserted on whatever the fixture happened to be, for the
   * same reason the KYC suite drives two transitions: a client that was already PENDING would
   * pass this test while proving nothing.
   *
   * <p>The introducing agent is asserted in the same journey because the same registration
   * produces it. This client was registered BY agent.senior, so the record must name them — that
   * is the link commission is bound to at issuance, and it is the only browser-level check that
   * the party_id claim actually reaches the party record.
   */
  test('corrects a client without disturbing their KYC, and keeps who introduced them', async ({
    page,
    browser,
  }) => {
    test.slow();
    const agentContext = await browser.newContext({ storageState: 'e2e/.auth/agent.json' });
    const agentPage = await agentContext.newPage();

    const fullName = `E2E Correct Me ${Date.now()}`;
    await agentPage.goto('/agents/customers/new');
    await agentPage.getByLabel('Full name').fill(fullName);
    await agentPage.getByLabel('Date of birth').fill(dmy('1990-05-12'));
    await agentPage.getByRole('button', { name: 'Register individual' }).click();
    await expect(agentPage.getByText('Registered', { exact: true })).toBeVisible({ timeout: 15_000 });
    const partyIdText = await agentPage.getByText(/^[0-9a-f]{8}-[0-9a-f]{4}-/).textContent();
    const partyId = (partyIdText ?? '').trim();
    await agentContext.close();

    await page.goto(`/staff/parties/${partyId}`);
    await expectStaffShellReady(page);

    // Registered BY an agent, so the record says who — distinct from "Registered by" above it,
    // which is a login id and resolves to nobody in particular.
    await expect(page.getByText('Introduced by')).toBeVisible({ timeout: 15_000 });

    // Verify them first, so the correction below has something to preserve. Same sequence as
    // staff-party-kyc.spec.ts, which is where this interaction is actually specified.
    await page.locator('input[type="file"]').setInputFiles({
      name: 'id-scan.png',
      mimeType: 'image/png',
      buffer: MINIMAL_PNG,
    });
    await expect(page.getByText(/Evidence uploaded:/)).toBeVisible({ timeout: 20_000 });
    await page.getByRole('button', { name: 'Verify' }).click();
    await page.getByRole('button', { name: 'Verify identity' }).click();
    await expect(page.getByText('Verified').first()).toBeVisible({ timeout: 20_000 });

    // Now correct them, including an IDENTITY field — the case where an implementation would
    // most plausibly decide a reset was the responsible thing to do.
    await page.getByRole('link', { name: 'Correct details' }).click();
    await expect(page).toHaveURL(`/staff/parties/${partyId}/edit`, { timeout: 15_000 });

    const corrected = `${fullName} Corrected`;
    await page.getByLabel('Full name').fill(corrected);
    // exact: true -- "Occupation class" contains "Occupation", so a substring match resolves to
    // both fields. The same trap this console has hit before on "Annual salary".
    await page.getByLabel('Occupation', { exact: true }).fill('Fisherman');
    await page.getByRole('button', { name: 'Save corrections' }).click();

    await expect(page).toHaveURL(`/staff/parties/${partyId}`, { timeout: 20_000 });
    await expect(page.getByRole('heading', { name: corrected })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('Fisherman')).toBeVisible();
    await expect(page.getByText('Verified').first()).toBeVisible({ timeout: 15_000 });
  });

  test('a client record shows every panel, populated from real data', async ({ page }) => {
    // Amina Owner is the seeded policyholder every other suite issues against, so
    // this client genuinely holds policies -- the panels are not empty scaffolding.
    await page.goto('/staff/clients/individuals');
    await expectStaffShellReady(page);
    await page.getByLabel('Search by name').fill('Amina Owner');
    await page.getByLabel('Search by name').press('Enter');
    await expect(page.getByText('Amina Owner').first()).toBeVisible({ timeout: 15_000 });
    await page.getByText('Amina Owner').first().click();
    await expect(page).toHaveURL(/\/staff\/parties\/[0-9a-f-]{36}$/, { timeout: 15_000 });

    for (const panel of [
      'Policies',
      'Claims',
      'Underwriting',
      'Named as beneficiary',
      'KYC verification',
      'Identity',
      'Documents',
      'Also an agent',
    ]) {
      await expect(page.getByRole('heading', { name: panel, exact: true })).toBeVisible();
    }

    // Identity fields that were stored since the first migration and returned by no
    // endpoint at all until PartyDetailView existed.
    await expect(page.getByText('Date of birth')).toBeVisible();
    await expect(page.getByText('Registered by')).toBeVisible();

    // A real policy, linked.
    await expect(page.getByRole('link', { name: /^POL-/ }).first()).toBeVisible({ timeout: 20_000 });

    // This client holds hundreds of policies, so the panel caps and says so rather
    // than burying every panel beneath it. The link has to go somewhere real.
    await expect(page.getByText(/more not shown/).first()).toBeVisible();
    await page.getByRole('link', { name: 'View all' }).click();
    await expect(page).toHaveURL(/policyholderPartyId=/);
    await expect(page.getByRole('heading', { name: 'Policies' })).toBeVisible({ timeout: 15_000 });
  });

  /**
   * The split itself, and the only assertions that can prove it is real.
   *
   * Every one of these asserts something ABSENT, because a `partyType` filter that
   * was silently dropped -- misspelled parameter, wrong serialisation, ignored
   * server-side -- would still return the rows each area wanted and pass any check
   * that only looked for what should be there.
   */
  test('the two areas are separate lists, each filtered server-side', async ({ page }) => {
    await page.goto('/staff/clients/individuals');
    await expectStaffShellReady(page);
    await expect(page.getByRole('table', { name: 'Individual clients' })).toBeVisible({
      timeout: 30_000,
    });

    // The Type column is dropped here on purpose: every row is the same type, so a
    // column repeating it twenty times says nothing. Its absence is the assertion.
    await expect(page.getByRole('columnheader', { name: 'Type' })).not.toBeVisible();
    // And no organisation leaked into the individuals list.
    await expect(page.getByRole('cell', { name: 'Company', exact: true })).not.toBeVisible();

    await page.getByRole('link', { name: 'Corporate/Group' }).click();
    await expect(page).toHaveURL(/\/staff\/clients\/organisations$/);
    await expect(page.getByRole('heading', { name: 'Corporate & groups' })).toBeVisible({
      timeout: 15_000,
    });

    const organisations = page.getByRole('table', { name: 'Corporate and group clients' });
    const emptyOrganisations = page.getByText('No companies or groups yet');
    await expect(organisations.or(emptyOrganisations)).toBeVisible({ timeout: 15_000 });

    if (await organisations.isVisible()) {
      // Here the Type column IS the distinction between a company and a group, so it
      // is promoted rather than hidden below `sm` -- and it renders English, not
      // `CORPORATE`.
      await expect(page.getByRole('columnheader', { name: 'Type' })).toBeVisible();
      // `exact` matters: without it this matches every fixture row named
      // "E2E Corporate 1788..." and the assertion fails on its own test data rather
      // than on the SCREAMING_ENUM it is looking for.
      await expect(page.getByRole('cell', { name: 'CORPORATE', exact: true })).not.toBeVisible();
      // The whole point: no individual in the organisations list.
      await expect(page.getByRole('cell', { name: 'Individual', exact: true })).not.toBeVisible();
      // The count belongs to this area, not to the register.
      await expect(countLine(page, 'corporate & group clients')).toBeVisible();
    }
  });

  test('search works in the organisations area, against a real corporate', async ({
    page,
    browser,
  }) => {
    // Registers its own fixture rather than relying on a seeded corporate: the dev
    // tenant's seeded parties are individuals, so a search test over "whatever
    // corporates happen to exist" would pass vacuously against an empty list.
    //
    // Through the agents realm because that is the only realm with an onboarding
    // screen -- there is no staff registration route. Staff are not scoped by
    // `createdBy`, so the row is visible to this page regardless of who created it,
    // which is the same route the individual fixture above takes.
    const suffix = Date.now();
    const registeredName = `E2E Org Fixture ${suffix} Ltd`;
    const agentContext = await browser.newContext({ storageState: 'e2e/.auth/agent.json' });
    const agentPage = await agentContext.newPage();
    await agentPage.goto('/agents/customers/new');
    await agentPage.getByRole('button', { name: 'Corporate' }).click();
    await agentPage.getByLabel('Registered name').fill(registeredName);
    await agentPage.getByLabel('Registration number').fill(`E2E-ORG-${suffix}`);
    await agentPage.getByRole('button', { name: 'Register corporate' }).click();
    await expect(agentPage.getByText('Registered', { exact: true })).toBeVisible({
      timeout: 15_000,
    });
    await agentContext.close();

    await page.goto('/staff/clients/organisations');
    await expectStaffShellReady(page);
    await page.getByLabel('Search companies and groups by name').fill(registeredName);
    await page.getByLabel('Search companies and groups by name').press('Enter');
    await expect(page).toHaveURL(/[?&]q=/, { timeout: 10_000 });
    await expect(page.getByText(registeredName)).toBeVisible({ timeout: 15_000 });

    // The same name must NOT be findable in the individuals area -- which is the
    // server-side filter and the search composing, rather than one overriding the
    // other.
    await page.goto(`/staff/clients/individuals?q=${encodeURIComponent(registeredName)}`);
    await expect(page.getByText(/No individual clients matching/)).toBeVisible({ timeout: 15_000 });
  });

  test('the old register link still lands somewhere real, with its filter intact', async ({
    page,
  }) => {
    // `/staff/kyc` was the register for as long as it was one screen, so the KYC
    // badge's own links and staff bookmarks point at it. A redirect that dropped the
    // query string would silently answer a different question than the one asked.
    await page.goto('/staff/kyc?kycStatus=PENDING');
    await expectStaffShellReady(page);
    await expect(page).toHaveURL('/staff/clients/individuals?kycStatus=PENDING', {
      timeout: 15_000,
    });
    await expect(countLine(page, 'pending individual clients')).toBeVisible({ timeout: 15_000 });

    // And the group's own path, which is the obvious thing to type when both items
    // live under it. Without a route it falls through the catch-all to the realm
    // picker, which reads as the console having logged you out.
    await page.goto('/staff/clients');
    await expect(page).toHaveURL('/staff/clients/individuals', { timeout: 15_000 });
    await expect(page.getByRole('heading', { name: 'Individual clients' })).toBeVisible({
      timeout: 15_000,
    });
  });
});

test.describe('agents my clients', () => {
  test.use({ storageState: 'e2e/.auth/agent.json' });

  test('sees the clients it registered, and a record without staff-only panels', async ({ page }) => {
    await page.goto('/agents/clients');
    await expect(page.getByRole('heading', { name: 'My clients' })).toBeVisible({ timeout: 30_000 });

    const table = page.getByRole('table', { name: 'Clients' });
    await expect(table).toBeVisible({ timeout: 15_000 });
    await table.getByRole('cell').first().click();
    await expect(page).toHaveURL(/\/agents\/parties\/[0-9a-f-]{36}$/, { timeout: 15_000 });

    // "Your Policies", not "Policies": an agent's policies are scoped to their
    // hierarchy team's book while this register is built on who they REGISTERED, so
    // an empty panel here means "not in your book" rather than "this client has none".
    await expect(page.getByRole('heading', { name: 'Your Policies' })).toBeVisible();

    // Staff-only panels must not render for an agent. `POST /parties/{id}/kyc` is
    // staff-only and `GET /agents` is staff-only, so showing either here would be a
    // control that 403s on use.
    await expect(page.getByRole('heading', { name: 'KYC verification' })).not.toBeVisible();
    await expect(page.getByRole('heading', { name: 'Also an agent' })).not.toBeVisible();
  });
});
