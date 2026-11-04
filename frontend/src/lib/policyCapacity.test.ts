import { describe, expect, it } from 'vitest';
import { claimWarning } from './policyCapacity';

describe('claimWarning', () => {
  it('says nothing about a policy on cover', () => {
    expect(claimWarning({ status: 'ACTIVE', productCategory: 'TERM_LIFE' })).toBeNull();
  });

  it('says an offer is not yet on cover, and never asks to reinstate it', () => {
    const warning = claimWarning({ status: 'PROPOSED', productCategory: 'TERM_LIFE' });
    expect(warning).toMatch(/not yet on cover/);
    expect(warning).not.toMatch(/reinstate/i);
  });

  it('takes a claim on a lapsed policy for an event before the lapse', () => {
    expect(claimWarning({ status: 'LAPSED', productCategory: 'TERM_LIFE' }))
      .toBe('Cover has ended (lapsed). A claim is accepted only for an event before it ended.');
  });

  it('refuses a claim on a scheme not in force', () => {
    expect(claimWarning({ status: 'LAPSED', productCategory: 'CREDIT_LIFE' })).toMatch(/scheme is not in force/);
    expect(claimWarning({ status: 'ACTIVE', productCategory: 'GROUP_LIFE' })).toBeNull();
  });
});
