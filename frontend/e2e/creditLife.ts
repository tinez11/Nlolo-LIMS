import { expect, request as apiRequest, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * A credit-life scheme with one pending enrolment file, built over the API for the browser to
 * act on.
 *
 * ## Why this fixture is not built through the console
 *
 * The console CAN set one up now — `IssueCreditLifeSchemePage`, and a spec below drives it. This
 * fixture exists anyway, and deliberately, for the specs that are about what happens to a scheme
 * rather than about creating one. Going through the form every time would make every assertion
 * about enrolment depend on the set-up form still working, so one broken field would fail the
 * lot and none of the failures would name the enrolment behaviour they were meant to check.
 *
 * ## What is still real
 *
 * Everything. The token is a real password grant against the real `staff` realm, minted for
 * `staff.admin` — the same identity and the same confidential client the dev seeder uses. There
 * is no fabricated JWT here and there must never be one: this platform has twice shipped a green
 * suite over a credential path that did not work, because the tests minted their own identities
 * (see `playwright.config.ts`). What this file does is skip the *screens*, never the *auth*.
 *
 * ## The uploader is deliberately not the browser's identity
 *
 * The file is submitted here as `staff.admin`, while the spec runs as `staff.underwriter`. That
 * asymmetry is the whole point of the fixture: acceptance must be a second person, and a spec
 * that uploaded and accepted as one identity would prove the opposite of what it claims — the
 * backend would refuse it, and a passing test would mean the refusal had stopped working.
 */

const API = 'http://localhost:8080';
const KEYCLOAK = 'http://localhost:8081';
const PASSWORD = 'devpassword';

/**
 * The confidential client's secret, read from the realm import rather than hardcoded.
 *
 * `lifeplatform-spa` — the client the SPA itself uses — is public with direct access grants
 * DISABLED, so it cannot mint a token outside a browser and is the wrong tool here.
 * `lifeplatform-app` is confidential with grants enabled, which is exactly what a server-side
 * fixture needs. Resolved from the repo root because Playwright runs with `frontend/` as its
 * working directory, the same assumption `playwright.config.ts` already makes about
 * `../backend/infra/docker-compose.yml`.
 */
function staffClientSecret(): string {
  const realm = JSON.parse(
    readFileSync(resolve('../backend/keycloak/staff-realm.json'), 'utf8'),
  ) as { clients?: { clientId?: string; secret?: string }[] };
  const secret = realm.clients?.find((c) => c.clientId === 'lifeplatform-app')?.secret;
  if (!secret) throw new Error('lifeplatform-app has no secret in backend/keycloak/staff-realm.json');
  return secret;
}

/** A real access token for a real staff user. */
export async function staffToken(http: APIRequestContext, username: string): Promise<string> {
  const response = await http.post(`${KEYCLOAK}/realms/staff/protocol/openid-connect/token`, {
    form: {
      grant_type: 'password',
      client_id: 'lifeplatform-app',
      client_secret: staffClientSecret(),
      username,
      password: PASSWORD,
    },
  });
  expect(response.ok(), `Keycloak refused a token for ${username}`).toBeTruthy();
  const body = (await response.json()) as { access_token?: string };
  const token = body.access_token;
  if (!token) throw new Error(`No access_token for ${username}`);
  return token;
}

export interface CreditLifeScheme {
  policyNumber: string;
  /** The corporate lender that holds the scheme, by the name the console will render. */
  lenderName: string;
  lenderPartyId: string;
  /** The pending enrolment submission uploaded by `staff.admin`, awaiting a second person. */
  submissionId: string;
}

/**
 * The lender's monthly schedule, with two rows that must be refused.
 *
 * Copied in shape from `backend/docs/superpowers/specs/credit-life-enrolment-sample.csv`, which
 * is the template the lenders are given. The two bad rows are the reason the file exists in a
 * browser test at all: a refused row is a borrower with no insurance whose lender may believe
 * otherwise, and the page's central claim is that those reasons are visible without hunting.
 *
 *  - line 4 has no `borrower_full_name` — refused MISSING_REQUIRED_FIELD
 *  - line 5 is disbursed in 2027 — refused DISBURSEMENT_DATE_IN_FUTURE
 *
 * `member_reference` is empty on every row, which is correct and load-bearing: the lender has
 * never seen one, because the INSURER mints it on acceptance. Learning it is what the report is
 * for.
 */
export const ENROLMENT_CSV = [
  'member_reference,borrower_full_name,borrower_date_of_birth,borrower_sex,borrower_national_id,borrower_phone,loan_principal_amount,loan_term_months,disbursement_date',
  ',Juma Rajabu Kimaro,1990-07-02,M,,+255713111222,1200000.00,24,2026-07-15',
  ',Neema Joseph Massawe,1985-11-20,F,,,3400000.00,36,2026-07-20',
  ',,1979-02-11,M,,,900000.00,12,2026-07-22',
  ',Hamisi Salum Ally,1995-05-09,M,,,1500000.00,18,2027-12-01',
  ',Zainabu Omary Mbwana,1963-01-30,F,,,800000.00,6,2026-07-25',
  '',
].join('\n');

/** How many of the five rows pass, and how many do not. Asserted on screen by the spec. */
export const ENROLMENT_PASSING = 3;
export const ENROLMENT_REFUSED = 2;

async function postJson(
  http: APIRequestContext,
  token: string,
  path: string,
  data: unknown,
): Promise<Record<string, unknown>> {
  const response = await http.post(`${API}${path}`, {
    headers: {
      Authorization: `Bearer ${token}`,
      'Idempotency-Key': randomUUID(),
    },
    data: data as object,
  });
  const body = await response.text();
  expect(response.ok(), `POST ${path} -> ${response.status()} ${body}`).toBeTruthy();
  // Publishing a product version answers 201 with a Location header and NO body, so parsing
  // unconditionally turns a successful call into "Unexpected end of JSON input" -- a failure that
  // names the parser rather than the request, three calls before the one that actually needs it.
  return body ? (JSON.parse(body) as Record<string, unknown>) : {};
}

export interface CreditLifeFixtures {
  lenderName: string;
  lenderPartyId: string;
  productId: string;
  /** Exactly as the product <select> renders it: "name (code)". */
  productLabel: string;
  productVersionId: string;
}

/**
 * A lender and a published credit-life product — everything a scheme needs except the scheme.
 *
 * Split out from `seedCreditLifeScheme` so a spec can drive the console's own set-up form
 * against real, freshly authored products rather than asserting it against whatever a shared
 * dev database happens to hold.
 */
export async function seedCreditLifeFixtures(
  http: APIRequestContext,
  token: string,
): Promise<CreditLifeFixtures> {
  const suffix = Date.now().toString().slice(-8);
  const lenderName = `E2E Microfinance ${suffix}`;

  const lender = await postJson(http, token, '/parties/corporates', {
    registeredName: lenderName,
    registrationNumber: `REG-E2E-${suffix}`,
    contactInfo: { phoneNumber: '+255712000111', email: `ops-${suffix}@lender.example` },
  });

  const productName = `E2E Credit Life ${suffix}`;
  const productCode = `CL-E2E-${suffix}`;
  const product = await postJson(http, token, '/products', {
    productCode,
    productName,
    category: 'CREDIT_LIFE',
    defaultCurrency: 'TZS',
  });
  const productId = product.productId as string;

  // A version must be PUBLISHED before anything can be issued against it, the rating table must
  // cover AGE and SUM_ASSURED_BAND, and the TIRA filing is mandatory as of V12 -- the same three
  // rules the group-scheme spec's product fixture obeys through the form.
  await postJson(http, token, `/products/${productId}/versions`, {
    ifrsMeasurementModel: 'PAA',
    effectiveDate: '2026-01-01',
    tiraFiling: { reference: `TIRA/E2E/CL/${suffix}`, approvalDate: '2026-01-15' },
    ratingTable: [
      { factorType: 'AGE', band: '18-70', multiplier: 1.0, ageFrom: 18, ageTo: 70 },
      { factorType: 'SUM_ASSURED_BAND', band: 'LOW', multiplier: 1.0 },
    ],
    benefitSchedule: [{ benefitType: 'DEATH', calculationMethod: 'SUM_ASSURED' }],
  });

  const snapshot = await (
    await http.get(`${API}/products/${productId}/active-snapshot`, {
      headers: { Authorization: `Bearer ${token}` },
    })
  ).json();
  const productVersionId = snapshot.productVersionId as string;

  const exclusions = await http.put(
    `${API}/products/${productId}/versions/${productVersionId}/exclusion-periods`,
    {
      headers: { Authorization: `Bearer ${token}` },
      data: { suicideExclusionMonths: 12, preExistingExclusionMonths: 12 },
    },
  );
  expect(exclusions.ok(), `exclusion-periods -> ${exclusions.status()}`).toBeTruthy();

  return {
    lenderName,
    lenderPartyId: lender.partyId as string,
    productId,
    productLabel: `${productName} (${productCode})`,
    productVersionId,
  };
}

/**
 * Creates a lender, a CREDIT_LIFE product, a scheme with one opening borrower, and uploads a
 * pending enrolment file against it.
 *
 * Authors its own product rather than looking for a seeded one, the same choice
 * `staff-group-schemes.spec.ts` makes and for the same reason: a spec that hunted for "some
 * credit-life product" would pass vacuously on a tenant that had none.
 */
export async function seedCreditLifeScheme(): Promise<CreditLifeScheme> {
  const http = await apiRequest.newContext();
  try {
    const token = await staffToken(http, 'staff.admin');
    const { lenderName, lenderPartyId, productId, productVersionId } =
      await seedCreditLifeFixtures(http, token);


    /*
     * The scheme itself. Two things here are credit-life specific and were unreachable over HTTP
     * until 2026-09-23: `benefitBasis: AMORTISING_LOAN` with `loanTerms` on the member, and the
     * scheme-level `interestMethod` / `repaymentFrequency` / `premiumRatePercent`. The request DTO
     * described employer schemes only, so no credit-life scheme could be created through the API
     * at all however complete the domain behind it was.
     *
     * MIGRATION, for the reason `issueRealPolicy` documents at length: everything this spec does
     * needs a scheme IN FORCE, and this console has no action that accepts an offer.
     */
    const scheme = await postJson(http, token, '/group-schemes', {
      policyholderPartyId: lenderPartyId,
      productId,
      productVersionId,
      benefitBasis: 'AMORTISING_LOAN',
      fclAmount: '600000000.00',
      currency: 'TZS',
      openingSchedule: [
        {
          memberType: 'FREEFORM',
          memberName: 'Amina Hassan Mwinyi',
          dateOfBirth: '1988-03-14',
          loanTerms: {
            principalAmount: '2400000.00',
            annualInterestRatePercent: 0.0,
            termMonths: 18,
            repaymentFrequency: 'MONTHLY',
            disbursementDate: '2026-08-03',
            firstRepaymentDate: '2026-09-03',
          },
        },
      ],
      premium: { amount: '52000.00', currencyCode: 'TZS' },
      premiumFrequency: 'SINGLE',
      commencementDate: '2026-06-01',
      reasonForManualIssue: 'E2E credit-life console fixture',
      issuanceBasis: 'MIGRATION',
      interestMethod: 'FLAT_RATE',
      repaymentFrequency: 'MONTHLY',
      premiumRatePercent: '0.5000',
      /*
        A rate and what that rate MEANS are two different things, and the server refuses one
        without the other (policy V25's chk_group_scheme_premium_basis_iff_rate). Omitted here,
        every credit-life spec died on the same 409 at its first line -- so the whole credit-life
        console suite was dead from the commit that added the rule.

        PER_ANNUM_ON_PRINCIPAL specifically, because that is what these specs' figures were
        computed under: it is the basis V25 backfilled onto every scheme that predates the column,
        and the one `CreditLifePremium.forLoan`'s legacy overload still assumes. FLAT_ON_PRINCIPAL
        drops the term multiplier -- 0.5% of 2,400,000 once rather than over 18 months -- which
        silently rewrites every premium, invoice and commission figure downstream.
      */
      premiumBasis: 'PER_ANNUM_ON_PRINCIPAL',
    });
    const policyNumber = scheme.policyNumber as string;

    const upload = await http.post(`${API}/credit-life-schemes/${policyNumber}/enrolments`, {
      headers: { Authorization: `Bearer ${token}` },
      multipart: {
        file: {
          name: 'january-schedule.csv',
          mimeType: 'text/csv',
          buffer: Buffer.from(ENROLMENT_CSV, 'utf8'),
        },
      },
    });
    expect(
      upload.ok(),
      `enrolment upload -> ${upload.status()} ${await upload.text()}`,
    ).toBeTruthy();
    const submission = (await upload.json()) as Record<string, unknown>;

    // The upload judged every row and enrolled nobody. If this ever comes back ACCEPTED, the
    // two-person rule has been removed and the spec below is asserting a world that is gone.
    expect(submission.status, 'an uploaded file must arrive PENDING').toBe('PENDING');
    expect(submission.rejectedCount).toBe(ENROLMENT_REFUSED);
    expect(submission.enrolledCount).toBe(0);

    return { policyNumber, lenderName, lenderPartyId, submissionId: submission.submissionId as string };
  } finally {
    await http.dispose();
  }
}
