import { describe, expect, it } from 'vitest';
import { policyIssueFormSchema, toApiRequest } from './policyIssueForm';

const valid = () => ({
  policyholderPartyId: '11111111-1111-4111-8111-111111111111',
  productId: '22222222-2222-4222-8222-222222222222',
  productVersionId: '33333333-3333-4333-8333-333333333333',
  sumAssuredAmount: '2000000.00',
  sumAssuredCurrency: 'TZS',
  premiumAmount: '800.00',
  premiumCurrency: 'TZS',
  premiumFrequency: 'MONTHLY' as const,
  agentOfRecordId: '',
  reasonForManualIssue: 'Backfilling a legacy paper policy',
  beneficiaries: [],
});

describe('policyIssueFormSchema', () => {
  it('accepts a well-formed request with no beneficiaries and no agent', () => {
    expect(policyIssueFormSchema.safeParse(valid()).success).toBe(true);
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
    for (const premiumFrequency of ['MONTHLY', 'QUARTERLY', 'ANNUALLY'] as const) {
      expect(policyIssueFormSchema.safeParse({ ...valid(), premiumFrequency }).success).toBe(true);
    }
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

  it('mints a fresh underwritingCaseId every call', () => {
    const parsed = policyIssueFormSchema.parse(valid());
    const first = toApiRequest(parsed).underwritingCaseId;
    const second = toApiRequest(parsed).underwritingCaseId;
    expect(first).not.toBe(second);
  });

  it('produces an empty beneficiaries array from an empty form', () => {
    expect(toApiRequest(policyIssueFormSchema.parse(valid())).beneficiaries).toEqual([]);
  });
});
