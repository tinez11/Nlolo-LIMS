import { describe, expect, it } from 'vitest';
import {
  blankPolicyIssueForm,
  maturityPreview,
  policyIssueFormSchema,
  toApiRequest,
} from './policyIssueForm';

// Built on the blank form so the fixture always carries every key a real submission
// has -- react-hook-form initialises from blankPolicyIssueForm(), so a partial literal
// would be testing a shape the form never produces.
const valid = () => ({
  ...blankPolicyIssueForm(),
  underwritingCaseId: '55555555-5555-4555-8555-555555555555',
  policyholderPartyId: '11111111-1111-4111-8111-111111111111',
  productId: '22222222-2222-4222-8222-222222222222',
  productVersionId: '33333333-3333-4333-8333-333333333333',
  sumAssuredAmount: '2000000.00',
  sumAssuredCurrency: 'TZS',
  premiumAmount: '800.00',
  premiumCurrency: 'TZS',
  premiumFrequency: 'MONTHLY' as const,
  agentOfRecordId: '',
  // MIGRATION because that is what this fixture's own reason describes. Not an arbitrary pick:
  // the basis decides whether cover starts at issuance, so a fixture whose basis contradicts its
  // stated reason would be modelling something nobody would ever submit.
  issuanceBasis: 'MIGRATION' as const,
  reasonForManualIssue: 'Backfilling a legacy paper policy',
  beneficiaries: [],
});

describe('policyIssueFormSchema', () => {
  it('accepts a well-formed request with no beneficiaries and no agent', () => {
    expect(policyIssueFormSchema.safeParse(valid()).success).toBe(true);
  });

  it('refuses a manual issuance that does not say why', () => {
    const result = policyIssueFormSchema.safeParse({ ...valid(), issuanceBasis: '' });
    expect(result.success).toBe(false);
  });

  it('starts the basis unset, so the form never picks one for the user', () => {
    // Three of the five bases put the contract on risk before anybody has paid for it, so a
    // default here would be the form making that choice silently.
    expect(blankPolicyIssueForm().issuanceBasis).toBe('');
    expect(policyIssueFormSchema.safeParse(blankPolicyIssueForm()).success).toBe(false);
  });

  it('sends the basis through unchanged', () => {
    const parsed = policyIssueFormSchema.parse({ ...valid(), issuanceBasis: 'MIGRATION' });
    expect(toApiRequest(parsed).issuanceBasis).toBe('MIGRATION');
  });

  it('sends a basis that does not start cover through just as unchanged', () => {
    // The pair matters: a toApiRequest that hardcoded MIGRATION would satisfy the test above.
    const parsed = policyIssueFormSchema.parse({ ...valid(), issuanceBasis: 'UNDERWRITING_OVERRIDE' });
    expect(toApiRequest(parsed).issuanceBasis).toBe('UNDERWRITING_OVERRIDE');
  });

  it('accepts a well-formed request with an agent and beneficiaries summing to 100', () => {
    const result = policyIssueFormSchema.safeParse({
      ...valid(),
      agentOfRecordId: '44444444-4444-4444-8444-444444444444',
      beneficiaries: [
        { type: 'FREEFORM', partyId: '', freeformDesignee: 'My Estate', sharePercent: 100, revocable: true },
      ],
    });
    expect(result.success).toBe(true);
  });

  it('rejects beneficiaries that do not sum to 100 -- reuses beneficiaryListSchema verbatim', () => {
    const result = policyIssueFormSchema.safeParse({
      ...valid(),
      beneficiaries: [
        { type: 'FREEFORM', partyId: '', freeformDesignee: 'Only half', sharePercent: 50, revocable: true },
      ],
    });
    expect(result.success).toBe(false);
  });

  it('rejects a malformed policyholder party id', () => {
    expect(policyIssueFormSchema.safeParse({ ...valid(), policyholderPartyId: 'nope' }).success).toBe(
      false,
    );
  });

  it('rejects a blank product selection', () => {
    expect(policyIssueFormSchema.safeParse({ ...valid(), productId: '' }).success).toBe(false);
  });

  // policy.infrastructure.MoneyDto: ^-?\d+(\.\d{1,2})?$ AND @DecimalMin("0.01") --
  // the regex alone would admit 0 or a negative amount, so the floor is a second,
  // independent check, same as the backend's two-annotation shape.
  it.each(['0', '0.00', '-100.00', 'abc', '1.555'])(
    'rejects an invalid sumAssuredAmount of %s',
    (sumAssuredAmount) => {
      expect(policyIssueFormSchema.safeParse({ ...valid(), sumAssuredAmount }).success).toBe(false);
    },
  );

  it.each(['0', '0.00', '-1.00'])('rejects an invalid premiumAmount of %s', (premiumAmount) => {
    expect(policyIssueFormSchema.safeParse({ ...valid(), premiumAmount }).success).toBe(false);
  });

  it('accepts the smallest valid amount, 0.01', () => {
    expect(
      policyIssueFormSchema.safeParse({ ...valid(), sumAssuredAmount: '0.01', premiumAmount: '0.01' })
        .success,
    ).toBe(true);
  });

  it('rejects a currency code that is not 3 uppercase letters', () => {
    expect(policyIssueFormSchema.safeParse({ ...valid(), sumAssuredCurrency: 'tzs' }).success).toBe(
      false,
    );
    expect(policyIssueFormSchema.safeParse({ ...valid(), sumAssuredCurrency: 'TZ' }).success).toBe(
      false,
    );
  });

  it('rejects a blank reason for manual issue', () => {
    expect(policyIssueFormSchema.safeParse({ ...valid(), reasonForManualIssue: '' }).success).toBe(
      false,
    );
  });

  it('rejects a malformed agent id when one is provided, but allows blank', () => {
    expect(policyIssueFormSchema.safeParse({ ...valid(), agentOfRecordId: 'nope' }).success).toBe(
      false,
    );
    expect(policyIssueFormSchema.safeParse({ ...valid(), agentOfRecordId: '' }).success).toBe(true);
  });

  it('accepts every premium frequency the spec declares', () => {
    for (const premiumFrequency of ['MONTHLY', 'QUARTERLY', 'ANNUALLY', 'SINGLE'] as const) {
      expect(policyIssueFormSchema.safeParse({ ...valid(), premiumFrequency }).success).toBe(true);
    }
  });

  /**
   * SINGLE was unreachable from this console until the whole path was opened: the dropdown,
   * this schema, the generated types and the quote endpoint's own enum all stopped at
   * ANNUALLY, so a single-premium product could be priced by the backend and never sold.
   */
  it('accepts a single premium paid once over a twelve-month term', () => {
    const result = policyIssueFormSchema.safeParse({
      ...valid(),
      premiumFrequency: 'SINGLE' as const,
      commencementDate: '2026-09-29',
      policyTermMonths: '12',
      premiumPayingTermMonths: '1',
    });
    expect(result.success).toBe(true);
  });

  it('refuses a single premium spread across several months', () => {
    // Mirrors the SINGLE arm of Policy.applyTerm. The two fields were free to disagree, and
    // the dev database holds the proof: policies carrying MONTHLY with a paying term of 1,
    // which reads as "monthly instalments, paid for one month" and is neither.
    const result = policyIssueFormSchema.safeParse({
      ...valid(),
      premiumFrequency: 'SINGLE' as const,
      commencementDate: '2026-09-29',
      policyTermMonths: '12',
      premiumPayingTermMonths: '6',
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues.map((i) => i.path.join('.'))).toContain('premiumPayingTermMonths');
      expect(result.error.issues.map((i) => i.message).join(' ')).toMatch(/charged once/);
    }
  });

  it('leaves a blank paying term alone on a single premium', () => {
    // Absent is not the same as wrong: the backend accepts a null paying term, and the form
    // must not invent a 1 the user did not type.
    const result = policyIssueFormSchema.safeParse({
      ...valid(),
      premiumFrequency: 'SINGLE' as const,
      commencementDate: '2026-09-29',
      policyTermMonths: '12',
      premiumPayingTermMonths: '',
    });
    expect(result.success).toBe(true);
  });
});

describe('the underwriting case a manual issue is made against', () => {
  it('refuses a submission that names no case, rather than inventing one', () => {
    const result = policyIssueFormSchema.safeParse({ ...valid(), underwritingCaseId: '' });
    expect(result.success).toBe(false);
    const messages = result.success
      ? []
      : result.error.issues.filter((i) => i.path[0] === 'underwritingCaseId').map((i) => i.message);
    expect(messages).toContain('Choose the underwriting case this policy is issued from');
  });

  it('refuses something that is not a case id', () => {
    const result = policyIssueFormSchema.safeParse({ ...valid(), underwritingCaseId: 'not-a-uuid' });
    expect(result.success).toBe(false);
  });

  /**
   * The regression guard. `toApiRequest` used to return `crypto.randomUUID()` here, so every
   * manual issue pointed at a case that did not exist -- which defeated the server's
   * duplicate check (random ids never collide) and made contestability resolve to nothing
   * while looking like a real answer.
   */
  it('sends the case it was given, unchanged, and never a fresh one', () => {
    const caseId = '3f2504e0-4f89-41d3-9a0c-0305e82c3301';
    const parsed = policyIssueFormSchema.parse({ ...valid(), underwritingCaseId: caseId });

    expect(toApiRequest(parsed).underwritingCaseId).toBe(caseId);
    // Twice, because a synthesized id differs between calls and a fixed one does not.
    expect(toApiRequest(parsed).underwritingCaseId).toBe(toApiRequest(parsed).underwritingCaseId);
  });
});

describe('toApiRequest', () => {
  it('sends agentOfRecordId as null, never an empty string, when left blank', () => {
    const parsed = policyIssueFormSchema.parse(valid());
    const api = toApiRequest(parsed);
    // ManualIssueRequestDto's own javadoc: the JSON key must be present but its
    // value may be null (a direct/online channel with no agent) -- omitting the
    // key or sending "" is not the same contract.
    expect(api.agentOfRecordId).toBeNull();
  });

  it('sends a real agentOfRecordId through untouched', () => {
    const parsed = policyIssueFormSchema.parse({
      ...valid(),
      agentOfRecordId: '44444444-4444-4444-8444-444444444444',
    });
    expect(toApiRequest(parsed).agentOfRecordId).toBe('44444444-4444-4444-8444-444444444444');
  });

  it('nests amount and currency into Money objects', () => {
    const api = toApiRequest(policyIssueFormSchema.parse(valid()));
    expect(api.sumAssured).toEqual({ amount: '2000000.00', currencyCode: 'TZS' });
    expect(api.premiumAmount).toEqual({ amount: '800.00', currencyCode: 'TZS' });
  });

  // `mints a fresh underwritingCaseId every call` was here, asserting that every manual
  // issue invented its own case id. That was the defect stated as the contract: a fabricated
  // id points at no case, so the server's one-policy-per-case check could never fire, and
  // claims contestability resolved to nothing while looking like a real answer. The
  // replacement lives above, in "the underwriting case a manual issue is made against".

  it('produces an empty beneficiaries array from an empty form', () => {
    expect(toApiRequest(policyIssueFormSchema.parse(valid())).beneficiaries).toEqual([]);
  });
});

describe('the policy term', () => {
  const termed = () => ({
    ...valid(),
    commencementDate: '2026-03-01',
    policyTermMonths: '240',
    premiumPayingTermMonths: '120',
  });

  it('sends the term when it is given', () => {
    const request = toApiRequest(policyIssueFormSchema.parse(termed()));
    expect(request.commencementDate).toBe('2026-03-01');
    expect(request.policyTermMonths).toBe(240);
    expect(request.premiumPayingTermMonths).toBe(120);
  });

  // Whole life, an annuity and a renewable group scheme have no term. Omitting is the
  // honest encoding: 0 would trip policy_term_positive, and null would claim the
  // question was asked and answered.
  it('omits the term entirely for a product that does not term', () => {
    const request = toApiRequest(policyIssueFormSchema.parse(valid()));
    expect('commencementDate' in request).toBe(false);
    expect('policyTermMonths' in request).toBe(false);
    expect('premiumPayingTermMonths' in request).toBe(false);
  });

  it('never sends a maturity date -- the aggregate derives it', () => {
    const request = toApiRequest(policyIssueFormSchema.parse(termed()));
    expect('maturityDate' in request).toBe(false);
  });

  it('rejects a premium-paying term longer than the policy term', () => {
    const result = policyIssueFormSchema.safeParse({
      ...termed(),
      policyTermMonths: '120',
      premiumPayingTermMonths: '240',
    });
    expect(result.success).toBe(false);
  });

  it('accepts a premium-paying term equal to the policy term', () => {
    expect(
      policyIssueFormSchema.safeParse({ ...termed(), premiumPayingTermMonths: '240' }).success,
    ).toBe(true);
  });

  it('rejects a term with no commencement date to run from', () => {
    expect(
      policyIssueFormSchema.safeParse({ ...termed(), commencementDate: '' }).success,
    ).toBe(false);
  });

  // The reverse is legitimate: this is how whole life is recorded.
  it('accepts a commencement date with no term', () => {
    expect(
      policyIssueFormSchema.safeParse({
        ...valid(),
        commencementDate: '2026-03-01',
      }).success,
    ).toBe(true);
  });

  it('rejects a fractional or zero term', () => {
    expect(policyIssueFormSchema.safeParse({ ...termed(), policyTermMonths: '12.5' }).success).toBe(false);
    expect(policyIssueFormSchema.safeParse({ ...termed(), policyTermMonths: '0' }).success).toBe(false);
  });
});

describe('maturityPreview', () => {
  it('adds whole months', () => {
    expect(maturityPreview('2026-03-01', '240')).toBe('2046-03-01');
  });

  // Must match java.time.LocalDate.plusMonths, which clamps to the last day of a
  // shorter target month. A naive Date(+months) rolls 31 January into 3 March and
  // would show a date the backend disagrees with.
  it('clamps to the last day of a shorter month, like LocalDate.plusMonths', () => {
    expect(maturityPreview('2026-01-31', '1')).toBe('2026-02-28');
    expect(maturityPreview('2024-01-31', '1')).toBe('2024-02-29');
    expect(maturityPreview('2026-08-31', '1')).toBe('2026-09-30');
  });

  it('returns null when the inputs imply no maturity', () => {
    expect(maturityPreview('', '240')).toBeNull();
    expect(maturityPreview('2026-03-01', '')).toBeNull();
    expect(maturityPreview('2026-03-01', '0')).toBeNull();
    expect(maturityPreview('not-a-date', '240')).toBeNull();
  });
});
