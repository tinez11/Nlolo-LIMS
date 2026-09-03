import { describe, expect, it } from 'vitest';
import { blankOpenCaseForm, openCaseFormSchema, toApiRequest } from './openCaseForm';

// Built on the blank form so the fixture always carries every key a real submission has.
const valid = () => ({
  ...blankOpenCaseForm(),
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

describe('proposal identity', () => {
  const LIFE_ASSURED = '3f9a2c1e-8b4d-4e77-a1c2-9d0e5f6a7b8c';

  // The whole reason the field exists: a parent insuring a child, an employer its
  // staff. Before this the model had one party slot and could not say it.
  it('sends a life assured who is not the applicant', () => {
    const api = toApiRequest(
      openCaseFormSchema.parse({ ...valid(), lifeAssuredPartyId: LIFE_ASSURED }),
    );
    expect(api.lifeAssuredPartyId).toBe(LIFE_ASSURED);
  });

  // Blank is not a null to send. It means self-insured, and the backend resolves that
  // to the applicant rather than storing a null every later reader must interpret.
  it('omits the life assured entirely when the applicant insures themselves', () => {
    const api = toApiRequest(openCaseFormSchema.parse(valid()));
    expect('lifeAssuredPartyId' in api).toBe(false);
  });

  it('rejects a malformed life assured id', () => {
    expect(
      openCaseFormSchema.safeParse({ ...valid(), lifeAssuredPartyId: 'not-a-uuid' }).success,
    ).toBe(false);
  });

  it('sends branch, source and proposed commencement when given', () => {
    const api = toApiRequest(
      openCaseFormSchema.parse({
        ...valid(),
        branch: 'Ilala',
        sourceOfBusiness: 'Bancassurance',
        proposedCommencementDate: '2026-11-01',
      }),
    );
    expect(api.branch).toBe('Ilala');
    expect(api.sourceOfBusiness).toBe('Bancassurance');
    expect(api.proposedCommencementDate).toBe('2026-11-01');
  });

  it('omits each of them when blank rather than sending an empty string', () => {
    const api = toApiRequest(openCaseFormSchema.parse(valid()));
    expect('branch' in api).toBe(false);
    expect('sourceOfBusiness' in api).toBe(false);
    expect('proposedCommencementDate' in api).toBe(false);
  });

  // Free text on purpose -- the platform does not own this vocabulary until TIRA
  // publishes one -- so anything within the length limit is accepted.
  it('accepts any source of business within the length limit', () => {
    expect(
      openCaseFormSchema.safeParse({ ...valid(), sourceOfBusiness: 'Walk-in, referred by staff' })
        .success,
    ).toBe(true);
    expect(
      openCaseFormSchema.safeParse({ ...valid(), sourceOfBusiness: 'x'.repeat(61) }).success,
    ).toBe(false);
  });

  it('rejects a malformed proposed commencement date', () => {
    expect(
      openCaseFormSchema.safeParse({ ...valid(), proposedCommencementDate: '01/11/2026' }).success,
    ).toBe(false);
  });
});
