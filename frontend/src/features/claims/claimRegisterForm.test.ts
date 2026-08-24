import { describe, expect, it } from 'vitest';
import { registerClaimFormSchema, toApiRequest } from './claimRegisterForm';

const shared = {
  policyNumber: 'POL-6BD5702F',
  claimantPartyId: '11111111-1111-4111-8111-111111111111',
  dateOfEvent: '2026-08-01',
};

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
    expect(registerClaimFormSchema.safeParse(build()).success).toBe(true);
  });

  it('rejects a policy number that does not match PolicyNumberRef', () => {
    const result = registerClaimFormSchema.safeParse({ ...death(), policyNumber: 'x' });
    expect(result.success).toBe(false);
  });

  it('rejects a malformed claimant party id', () => {
    const result = registerClaimFormSchema.safeParse({ ...death(), claimantPartyId: 'not-a-uuid' });
    expect(result.success).toBe(false);
  });

  it('rejects a missing dateOfEvent', () => {
    const result = registerClaimFormSchema.safeParse({ ...death(), dateOfEvent: '' });
    expect(result.success).toBe(false);
  });

  describe('DEATH', () => {
    it.each(['causeOfDeath', 'placeOfDeath', 'dateOfDeath', 'attendingPhysician'])(
      'rejects a blank %s',
      (field) => {
        const value = death();
        const result = registerClaimFormSchema.safeParse({
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
        const result = registerClaimFormSchema.safeParse({
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
        const result = registerClaimFormSchema.safeParse({
          ...value,
          details: { ...value.details, impairmentPercent },
        });
        expect(result.success).toBe(false);
      },
    );

    it('accepts either boolean value for permanent', () => {
      const value = disability();
      expect(
        registerClaimFormSchema.safeParse({
          ...value,
          details: { ...value.details, permanent: false },
        }).success,
      ).toBe(true);
    });

    it.each(['disabilityType', 'onsetDate'])('rejects a blank %s', (field) => {
      const value = disability();
      const result = registerClaimFormSchema.safeParse({
        ...value,
        details: { ...value.details, [field]: '' },
      });
      expect(result.success).toBe(false);
    });
  });

  describe('CRITICAL_ILLNESS', () => {
    it.each(['diagnosis', 'diagnosisDate', 'icdCode'])('rejects a blank %s', (field) => {
      const value = criticalIllness();
      const result = registerClaimFormSchema.safeParse({
        ...value,
        details: { ...value.details, [field]: '' },
      });
      expect(result.success).toBe(false);
    });
  });

  describe('MATURITY', () => {
    it('rejects a blank maturityDate', () => {
      const value = maturity();
      const result = registerClaimFormSchema.safeParse({
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

  it('rejects an unrecognised claimType (no matching union branch)', () => {
    const result = registerClaimFormSchema.safeParse({
      ...shared,
      details: { claimType: 'INVENTED', foo: 'bar' },
    });
    expect(result.success).toBe(false);
  });
});

describe('toApiRequest', () => {
  it('derives the top-level claimType from details.claimType, never a separate input', () => {
    const parsed = registerClaimFormSchema.parse(death());
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
    const parsed = registerClaimFormSchema.parse(build());
    const api = toApiRequest(parsed);
    expect(api.policyNumber).toBe(shared.policyNumber);
    expect(api.claimantPartyId).toBe(shared.claimantPartyId);
    expect(api.dateOfEvent).toBe(shared.dateOfEvent);
    expect(api.details).toEqual(build().details);
  });
});
