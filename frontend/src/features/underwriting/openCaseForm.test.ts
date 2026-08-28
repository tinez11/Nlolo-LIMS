import { describe, expect, it } from 'vitest';
import { openCaseFormSchema, toApiRequest } from './openCaseForm';

const valid = () => ({
  applicantPartyId: 'd9937444-3873-4336-9cb7-addb486f3e1b',
  productId: '9924cbb2-8adb-4be4-b0a6-2835e4ad7373',
  productVersionId: '76d868df-d22d-4044-8107-42bb3ab5107c',
  sumAssuredAmount: '1500000.00',
  sumAssuredCurrency: 'TZS',
  // Blank is the common case -- a direct sale -- and the schema treats it as absent rather
  // than as a malformed uuid.
  agentOfRecordId: '',
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

describe('agent of record', () => {
  it('accepts a blank agent id as a direct sale', () => {
    expect(openCaseFormSchema.safeParse({ ...valid(), agentOfRecordId: '' }).success).toBe(true);
  });

  it('rejects a malformed agent id rather than sending it', () => {
    expect(openCaseFormSchema.safeParse({ ...valid(), agentOfRecordId: 'nope' }).success).toBe(false);
  });

  it('omits the agent id entirely when blank, rather than sending an empty string', () => {
    // The field is a nullable uuid on the wire. '' is neither a uuid nor an absence, and
    // sending it would be a 400 for the most common case there is.
    const api = toApiRequest(openCaseFormSchema.parse({ ...valid(), agentOfRecordId: '' }));
    expect(api).not.toHaveProperty('agentOfRecordId');
  });

  it('sends a real agent id through', () => {
    const agentId = '11111111-2222-3333-4444-555555555555';
    const api = toApiRequest(openCaseFormSchema.parse({ ...valid(), agentOfRecordId: agentId }));
    expect(api.agentOfRecordId).toBe(agentId);
  });
});
