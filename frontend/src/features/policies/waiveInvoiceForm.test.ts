import { describe, expect, it } from 'vitest';
import { waiveInvoiceFormSchema, toApiRequest } from './waiveInvoiceForm';

describe('waiveInvoiceFormSchema', () => {
  it('accepts a well-formed reason', () => {
    expect(
      waiveInvoiceFormSchema.safeParse({ reason: 'Goodwill gesture, customer hardship' }).success,
    ).toBe(true);
  });

  it('rejects a reason under 10 characters', () => {
    expect(waiveInvoiceFormSchema.safeParse({ reason: 'too short' }).success).toBe(false);
  });

  it('rejects a blank reason', () => {
    expect(waiveInvoiceFormSchema.safeParse({ reason: '' }).success).toBe(false);
  });

  it('accepts a reason of exactly 10 characters', () => {
    expect(waiveInvoiceFormSchema.safeParse({ reason: '1234567890' }).success).toBe(true);
  });
});

describe('toApiRequest', () => {
  it('trims the reason', () => {
    const request = toApiRequest(
      waiveInvoiceFormSchema.parse({ reason: '  Goodwill gesture  ' }),
    );
    expect(request.reason).toBe('Goodwill gesture');
  });
});
