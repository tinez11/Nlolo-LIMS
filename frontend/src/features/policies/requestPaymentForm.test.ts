import { describe, expect, it } from 'vitest';
import { requestPaymentFormSchema, toApiRequest } from './requestPaymentForm';

describe('requestPaymentFormSchema', () => {
  it('accepts a well-formed payer reference', () => {
    expect(requestPaymentFormSchema.safeParse({ payerRef: '255700000000' }).success).toBe(true);
  });

  it('rejects a blank payer reference', () => {
    expect(requestPaymentFormSchema.safeParse({ payerRef: '' }).success).toBe(false);
  });
});

describe('toApiRequest', () => {
  it('trims the payer reference', () => {
    const request = toApiRequest(
      requestPaymentFormSchema.parse({ payerRef: '  255700000000  ' }),
    );
    expect(request.payerRef).toBe('255700000000');
  });
});
