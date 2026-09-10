import { describe, expect, it } from 'vitest';
import { registerClaimFormSchema, toApiRequest } from './claimRegisterForm';

const shared = {
  policyNumber: 'POL-6BD5702F',
  // Blank = no member, which is what every case here is: an individual policy.
  policyMemberId: '',
  claimantPartyId: '11111111-1111-4111-8111-111111111111',
  dateOfEvent: '2026-08-01',
};

const MEMBER_ID = '22222222-2222-4222-8222-222222222222';

const death = () => ({
  ...shared,
  details: {
    claimType: 'DEATH' as const,
    causeOfDeath: 'Natural causes',
    placeOfDeath: 'Dar es Salaam',
    dateOfDeath: '2026-08-01',
    attendingPhysician: 'Dr. Juma',
  },
});

const disability = () => ({
  ...shared,
  details: {
    claimType: 'DISABILITY' as const,
    disabilityType: 'Loss of limb',
    onsetDate: '2026-08-01',
    permanent: true,
    impairmentPercent: '62.50',
  },
});

const criticalIllness = () => ({
  ...shared,
  details: {
    claimType: 'CRITICAL_ILLNESS' as const,
    diagnosis: 'Cancer',
    diagnosisDate: '2026-08-01',
    icdCode: 'C50',
  },
});

const maturity = () => ({
  ...shared,
  details: { claimType: 'MATURITY' as const, maturityDate: '2026-08-01' },
});

describe('registerClaimFormSchema', () => {
  it.each([
    ['DEATH', death],
    ['DISABILITY', disability],
    ['CRITICAL_ILLNESS', criticalIllness],
    ['MATURITY', maturity],
  ])('accepts a well-formed %s claim', (_label, build) => {
    expect(registerClaimFormSchema().safeParse(build()).success).toBe(true);
  });

  it('rejects a policy number that does not match PolicyNumberRef', () => {
    const result = registerClaimFormSchema().safeParse({ ...death(), policyNumber: 'x' });
    expect(result.success).toBe(false);
  });

  it('rejects a malformed claimant party id', () => {
    const result = registerClaimFormSchema().safeParse({ ...death(), claimantPartyId: 'not-a-uuid' });
    expect(result.success).toBe(false);
  });

  it('rejects a missing dateOfEvent', () => {
    const result = registerClaimFormSchema().safeParse({ ...death(), dateOfEvent: '' });
    expect(result.success).toBe(false);
  });

  describe('DEATH', () => {
    it.each(['causeOfDeath', 'placeOfDeath', 'dateOfDeath', 'attendingPhysician'])(
      'rejects a blank %s',
      (field) => {
        const value = death();
        const result = registerClaimFormSchema().safeParse({
          ...value,
          details: { ...value.details, [field]: '' },
        });
        expect(result.success).toBe(false);
      },
    );
  });

  describe('DISABILITY', () => {
    it.each(['62.5', '62.50', '0', '0.00', '100', '100.00'])(
      'accepts a well-formed impairmentPercent of %s',
      (impairmentPercent) => {
        const value = disability();
        const result = registerClaimFormSchema().safeParse({
          ...value,
          details: { ...value.details, impairmentPercent },
        });
        expect(result.success).toBe(true);
      },
    );

    // Mirrors DisabilityClaimDetails.impairmentPercent's exact wire pattern
    // (^\d+(\.\d{1,2})?$, 0-100) -- deliberately no leading '-', unlike Money.
    it.each(['-1', '100.01', '150', 'abc', '1.555', ''])(
      'rejects an invalid impairmentPercent of %s',
      (impairmentPercent) => {
        const value = disability();
        const result = registerClaimFormSchema().safeParse({
          ...value,
          details: { ...value.details, impairmentPercent },
        });
        expect(result.success).toBe(false);
      },
    );

    it('accepts either boolean value for permanent', () => {
      const value = disability();
      expect(
        registerClaimFormSchema().safeParse({
          ...value,
          details: { ...value.details, permanent: false },
        }).success,
      ).toBe(true);
    });

    it.each(['disabilityType', 'onsetDate'])('rejects a blank %s', (field) => {
      const value = disability();
      const result = registerClaimFormSchema().safeParse({
        ...value,
        details: { ...value.details, [field]: '' },
      });
      expect(result.success).toBe(false);
    });
  });

  describe('CRITICAL_ILLNESS', () => {
    it.each(['diagnosis', 'diagnosisDate', 'icdCode'])('rejects a blank %s', (field) => {
      const value = criticalIllness();
      const result = registerClaimFormSchema().safeParse({
        ...value,
        details: { ...value.details, [field]: '' },
      });
      expect(result.success).toBe(false);
    });
  });

  describe('MATURITY', () => {
    it('rejects a blank maturityDate', () => {
      const value = maturity();
      const result = registerClaimFormSchema().safeParse({
        ...value,
        details: { ...value.details, maturityDate: '' },
      });
      expect(result.success).toBe(false);
    });

    // Deliberately the thinnest variant -- MaturityClaimDetails carries only
    // maturityDate, matching Claim's auto-approve-eligible path (decideSettlement
    // skips the "requires an assessment" gate specifically for MATURITY).
    it('requires nothing beyond maturityDate and the shared fields', () => {
      expect(Object.keys(maturity().details)).toEqual(['claimType', 'maturityDate']);
    });
  });

  describe('the member a claim is for', () => {
    // A scheme insures many lives, so "a claim on GL-000123" names none of them. The server
    // refuses it too (409); this is here so the person filing finds out while they are still
    // looking at the form rather than after submitting it.
    it('requires a member when the policy is a group scheme', () => {
      const result = registerClaimFormSchema({ productCategory: 'GROUP_LIFE' }).safeParse(death());
      expect(result.success).toBe(false);
      expect(result.error?.issues.some((i) => i.path[0] === 'policyMemberId')).toBe(true);
    });

    it('accepts a group claim that names a member', () => {
      const result = registerClaimFormSchema({ productCategory: 'GROUP_LIFE' }).safeParse({
        ...death(),
        policyMemberId: MEMBER_ID,
      });
      expect(result.success).toBe(true);
    });

    it('rejects a malformed member id on a group scheme', () => {
      const result = registerClaimFormSchema({ productCategory: 'GROUP_LIFE' }).safeParse({
        ...death(),
        policyMemberId: 'not-a-uuid',
      });
      expect(result.success).toBe(false);
    });

    // Unreachable through the UI, which only renders the field for a scheme. Validated anyway:
    // the rule belongs to the contract, not to which inputs happen to be on screen today.
    it('rejects a member on an individual policy', () => {
      const result = registerClaimFormSchema({ productCategory: 'TERM_LIFE' }).safeParse({
        ...death(),
        policyMemberId: MEMBER_ID,
      });
      expect(result.success).toBe(false);
    });

    it('treats an unloaded policy as not a scheme, so the field stays optional', () => {
      // The category arrives a tick after the policy number is typed. Until it does, demanding
      // a member would flag an error against a question the form has not asked yet.
      expect(registerClaimFormSchema({}).safeParse(death()).success).toBe(true);
    });

    it('sends null rather than a blank string for an individual claim', () => {
      const api = toApiRequest(registerClaimFormSchema().parse(death()));
      expect(api.policyMemberId).toBeNull();
    });

    it('sends the member id for a group claim', () => {
      const api = toApiRequest(
        registerClaimFormSchema({ productCategory: 'GROUP_LIFE' }).parse({
          ...death(),
          policyMemberId: MEMBER_ID,
        }),
      );
      expect(api.policyMemberId).toBe(MEMBER_ID);
    });
  });

  it('rejects an unrecognised claimType (no matching union branch)', () => {
    const result = registerClaimFormSchema().safeParse({
      ...shared,
      details: { claimType: 'INVENTED', foo: 'bar' },
    });
    expect(result.success).toBe(false);
  });
});

describe('toApiRequest', () => {
  it('derives the top-level claimType from details.claimType, never a separate input', () => {
    const parsed = registerClaimFormSchema().parse(death());
    const api = toApiRequest(parsed);
    // Claim's own constructor 422s if these ever disagree -- deriving one from the
    // other makes that mismatch structurally impossible from this form.
    expect(api.claimType).toBe('DEATH');
    expect(api.details.claimType).toBe('DEATH');
  });

  it.each([
    ['DEATH', death],
    ['DISABILITY', disability],
    ['CRITICAL_ILLNESS', criticalIllness],
    ['MATURITY', maturity],
  ])('round-trips a %s claim into exactly the wire shape', (_label, build) => {
    const parsed = registerClaimFormSchema().parse(build());
    const api = toApiRequest(parsed);
    expect(api.policyNumber).toBe(shared.policyNumber);
    expect(api.claimantPartyId).toBe(shared.claimantPartyId);
    expect(api.dateOfEvent).toBe(shared.dateOfEvent);
    expect(api.details).toEqual(build().details);
  });
});
