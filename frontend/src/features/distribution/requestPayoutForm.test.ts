import { describe, expect, it } from 'vitest';
import { requestPayoutFormSchema, toApiRequest } from './requestPayoutForm';

describe('requestPayoutFormSchema', () => {
  it('accepts a well-formed payee reference', () => {
    expect(requestPayoutFormSchema.safeParse({ payeeRef: '255700000000' }).success).toBe(true);
  });

  it('rejects a blank payee reference', () => {
    expect(requestPayoutFormSchema.safeParse({ payeeRef: '' }).success).toBe(false);
  });
});

describe('toApiRequest', () => {
  it('trims the payee reference', () => {
    const request = toApiRequest(requestPayoutFormSchema.parse({ payeeRef: '  255700000000  ' }));
    expect(request.payeeRef).toBe('255700000000');
  });
});
