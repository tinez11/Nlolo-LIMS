import { describe, expect, it } from 'vitest';
import { suspendPolicyFormSchema, toApiRequest } from './suspendPolicyForm';

describe('suspendPolicyFormSchema', () => {
  it('accepts a well-formed reason', () => {
    expect(suspendPolicyFormSchema.safeParse({ reason: 'Employer group scheme in arrears' }).success).toBe(
      true,
    );
  });

  it('rejects a blank reason', () => {
    expect(suspendPolicyFormSchema.safeParse({ reason: '' }).success).toBe(false);
    expect(suspendPolicyFormSchema.safeParse({ reason: '   ' }).success).toBe(false);
  });
});

describe('toApiRequest', () => {
  it('trims the reason', () => {
    const request = toApiRequest(suspendPolicyFormSchema.parse({ reason: '  Arrears  ' }));
    expect(request.reason).toBe('Arrears');
  });
});
