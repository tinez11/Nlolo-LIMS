import { describe, expect, it } from 'vitest';
import { checkLabel, issueSplit, ownershipShare, tzs } from './shareRegister';

describe('share register helpers', () => {
  it('splits an issue into capital and premium', () => {
    expect(issueSplit(1_000_000, 1000, 1500)).toEqual({ capital: 1_000_000_000, premium: 500_000_000, total: 1_500_000_000 });
    expect(issueSplit(10, 1000, 1000)).toEqual({ capital: 10_000, premium: 0, total: 10_000 });
  });

  it('labels each check state', () => {
    expect(checkLabel('MET')).toBe('Met');
    expect(checkLabel('NOT_MET')).toBe('Not met');
    expect(checkLabel('NOT_SET')).toBe('Not set');
  });

  it('gives each holder a share of the issued shares', () => {
    expect(ownershipShare(400, 1000)).toBe('40.0%');
    expect(ownershipShare(0, 0)).toBe('—');
  });

  it('shows money in full, and nothing as a dash', () => {
    expect(tzs(1_000_000_000)).toBe('TZS 1,000,000,000.00');
    expect(tzs(null)).toBe('—');
  });
});
