import { describe, expect, it } from 'vitest';
import { EMPTY_CLAIM_FORM, missingForClaim, toClaimRequest } from './customerClaimForm';

const PARTY = '11111111-1111-1111-1111-111111111111';

describe('customer claim form', () => {
  it('asks a funeral claim who died, and sends that life with the accident flag', () => {
    const form = { ...EMPTY_CLAIM_FORM, policyNumber: 'POL-1', dateOfEvent: '2026-10-01', whatHappened: 'Heart attack',
      placeOfDeath: 'Mwananyamala Hospital', accidental: false };
    expect(missingForClaim(form, true)).toEqual(['Choose who died']);

    const request = toClaimRequest({ ...form, coveredLifeId: 'life-1' }, PARTY, true);
    expect(request).toMatchObject({ policyNumber: 'POL-1', claimantPartyId: PARTY, claimType: 'DEATH',
      coveredLifeId: 'life-1', accidental: false,
      details: { claimType: 'DEATH', causeOfDeath: 'Heart attack', dateOfDeath: '2026-10-01', attendingPhysician: 'Not known' } });
  });

  it('sends no covered life on a policy that is not funeral cover', () => {
    const request = toClaimRequest({ ...EMPTY_CLAIM_FORM, policyNumber: 'POL-2', claimType: 'MATURITY',
      dateOfEvent: '2026-10-01' }, PARTY, false);
    expect(request).not.toHaveProperty('coveredLifeId');
    expect(request.details).toEqual({ claimType: 'MATURITY', maturityDate: '2026-10-01' });
  });

  it('leaves the clinical fields to the assessor', () => {
    const illness = toClaimRequest({ ...EMPTY_CLAIM_FORM, policyNumber: 'P', claimType: 'CRITICAL_ILLNESS',
      dateOfEvent: '2026-09-01', whatHappened: 'Stroke' }, PARTY, false);
    expect(illness.details).toMatchObject({ diagnosis: 'Stroke', icdCode: 'Not known' });
    const disability = toClaimRequest({ ...EMPTY_CLAIM_FORM, policyNumber: 'P', claimType: 'DISABILITY',
      dateOfEvent: '2026-09-01', whatHappened: 'Lost a leg', permanent: true }, PARTY, false);
    expect(disability.details).toMatchObject({ disabilityType: 'Lost a leg', permanent: true, impairmentPercent: '0' });
  });

  it('needs what happened except on maturity', () => {
    expect(missingForClaim({ ...EMPTY_CLAIM_FORM, policyNumber: 'P', claimType: 'MATURITY', dateOfEvent: '2026-10-01' }, false))
      .toEqual([]);
    expect(missingForClaim({ ...EMPTY_CLAIM_FORM, policyNumber: 'P', claimType: 'DISABILITY', dateOfEvent: '2026-10-01' }, false))
      .toEqual(['Say what happened']);
  });
});
