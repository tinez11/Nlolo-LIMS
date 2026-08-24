import { describe, expect, it } from 'vitest';
import { openCaseFormSchema, toApiRequest } from './openCaseForm';

const valid = () => ({
  applicantPartyId: 'd9937444-3873-4336-9cb7-addb486f3e1b',
  productId: '9924cbb2-8adb-4be4-b0a6-2835e4ad7373',
  productVersionId: '76d868df-d22d-4044-8107-42bb3ab5107c',
  sumAssuredAmount: '1500000.00',
  sumAssuredCurrency: 'TZS',
});

describe('openCaseFormSchema', () => {
  it('accepts a well-formed request', () => {
    expect(openCaseFormSchema.safeParse(valid()).success).toBe(true);
  });

  it('rejects a malformed applicant party id', () => {
    expect(
      openCaseFormSchema.safeParse({ ...valid(), applicantPartyId: 'not-a-uuid' }).success,
    ).toBe(false);
  });

  it('rejects a blank applicant party id', () => {
    expect(openCaseFormSchema.safeParse({ ...valid(), applicantPartyId: '' }).success).toBe(false);
  });

  it('rejects no product selected', () => {
    expect(
      openCaseFormSchema.safeParse({ ...valid(), productId: '', productVersionId: '' }).success,
    ).toBe(false);
  });

  it('rejects a malformed sum assured amount', () => {
    expect(
      openCaseFormSchema.safeParse({ ...valid(), sumAssuredAmount: '1,500,000' }).success,
    ).toBe(false);
  });

  it('rejects a zero sum assured', () => {
    expect(openCaseFormSchema.safeParse({ ...valid(), sumAssuredAmount: '0.00' }).success).toBe(
      false,
    );
  });

  it('rejects a lowercase currency code', () => {
    expect(openCaseFormSchema.safeParse({ ...valid(), sumAssuredCurrency: 'tzs' }).success).toBe(
      false,
    );
  });
});

describe('toApiRequest', () => {
  it('nests sum assured as a Money object, never a bare number', () => {
    const request = toApiRequest(openCaseFormSchema.parse(valid()));
    expect(request.sumAssured).toEqual({ amount: '1500000.00', currencyCode: 'TZS' });
  });

  it('trims the applicant party id', () => {
    const request = toApiRequest(
      openCaseFormSchema.parse({ ...valid(), applicantPartyId: `  ${valid().applicantPartyId}  ` }),
    );
    expect(request.applicantPartyId).toBe(valid().applicantPartyId);
  });
});
