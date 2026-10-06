import { describe, expect, it } from 'vitest';
import { onboardAgentFormSchema, toApiRequest } from './onboardAgentForm';

const FUTURE_DATE = `${new Date().getFullYear() + 5}-01-01`;

const valid = () => ({
  partyId: 'd9937444-3873-4336-9cb7-addb486f3e1b',
  licenseNumber: 'LIC-0001',
  licenseExpiryDate: FUTURE_DATE,
  hierarchyParentId: '',
  salesChannel: 'AGENT' as const,
  homeBranch: 'DSM',
});

describe('onboardAgentFormSchema', () => {
  it('needs a home branch and refuses a channel no agent sells through (IFRS 17 I2)', () => {
    expect(onboardAgentFormSchema.safeParse({ ...valid(), homeBranch: '' }).success).toBe(false);
    expect(onboardAgentFormSchema.safeParse({ ...valid(), salesChannel: 'DIRECT' }).success).toBe(false);
    expect(toApiRequest(onboardAgentFormSchema.parse({ ...valid(), salesChannel: 'BROKER', homeBranch: 'ARU' })))
      .toMatchObject({ salesChannel: 'BROKER', homeBranch: 'ARU' });
  });

  it('accepts a well-formed request with no hierarchy parent', () => {
    expect(onboardAgentFormSchema.safeParse(valid()).success).toBe(true);
  });

  it('accepts a well-formed request with a hierarchy parent', () => {
    expect(
      onboardAgentFormSchema.safeParse({
        ...valid(),
        hierarchyParentId: '11111111-1111-1111-1111-111111111111',
      }).success,
    ).toBe(true);
  });

  it('rejects a malformed party id', () => {
    expect(onboardAgentFormSchema.safeParse({ ...valid(), partyId: 'not-a-uuid' }).success).toBe(
      false,
    );
  });

  it('rejects a blank license number', () => {
    expect(onboardAgentFormSchema.safeParse({ ...valid(), licenseNumber: '' }).success).toBe(
      false,
    );
  });

  it('rejects a license number over 50 characters', () => {
    expect(
      onboardAgentFormSchema.safeParse({ ...valid(), licenseNumber: 'X'.repeat(51) }).success,
    ).toBe(false);
  });

  it('accepts a license number of exactly 50 characters', () => {
    expect(
      onboardAgentFormSchema.safeParse({ ...valid(), licenseNumber: 'X'.repeat(50) }).success,
    ).toBe(true);
  });

  it('rejects a license expiry date that is not in the future', () => {
    expect(
      onboardAgentFormSchema.safeParse({ ...valid(), licenseExpiryDate: '2020-01-01' }).success,
    ).toBe(false);
  });

  it('rejects a malformed hierarchy parent id', () => {
    expect(
      onboardAgentFormSchema.safeParse({ ...valid(), hierarchyParentId: 'not-a-uuid' }).success,
    ).toBe(false);
  });
});

describe('toApiRequest', () => {
  it('sends null, not an empty string, for no hierarchy parent', () => {
    const request = toApiRequest(onboardAgentFormSchema.parse(valid()));
    expect(request.hierarchyParentId).toBeNull();
  });

  it('trims the license number', () => {
    const request = toApiRequest(
      onboardAgentFormSchema.parse({ ...valid(), licenseNumber: '  LIC-0001  ' }),
    );
    expect(request.licenseNumber).toBe('LIC-0001');
  });
});
