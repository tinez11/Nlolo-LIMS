import { describe, expect, it } from 'vitest';
import { reopenClaimFormSchema, toApiRequest } from './reopenClaimForm';

describe('reopenClaimFormSchema', () => {
  it('accepts a well-formed reason', () => {
    expect(reopenClaimFormSchema.safeParse({ reason: 'New evidence submitted' }).success).toBe(
      true,
    );
  });

  it('rejects a blank reason', () => {
    expect(reopenClaimFormSchema.safeParse({ reason: '' }).success).toBe(false);
  });

  it('rejects a whitespace-only reason', () => {
    expect(reopenClaimFormSchema.safeParse({ reason: '   ' }).success).toBe(false);
  });
});

describe('toApiRequest', () => {
  it('trims the reason', () => {
    const request = toApiRequest(reopenClaimFormSchema.parse({ reason: '  padded  ' }));
    expect(request.reason).toBe('padded');
  });
});
