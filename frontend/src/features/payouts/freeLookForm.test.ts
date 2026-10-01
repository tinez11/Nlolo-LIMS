import { describe, expect, it } from 'vitest';
import { blankFreeLook, freeLookSchema } from './freeLookForm';

describe('freeLookSchema', () => {
  it('needs a payee reference', () => {
    expect(freeLookSchema.safeParse(blankFreeLook()).success).toBe(false);
    expect(freeLookSchema.safeParse({ payeeRef: '   ', deductions: [] }).success).toBe(false);
  });

  it('accepts no deductions at all — the whole premium goes back', () => {
    expect(freeLookSchema.safeParse({ payeeRef: '+255700000009', deductions: [] }).success).toBe(true);
  });

  it('accepts an itemised deduction', () => {
    expect(
      freeLookSchema.safeParse({
        payeeRef: '+255700000009',
        deductions: [{ description: 'Medical examination', amount: '8000.00' }],
      }).success,
    ).toBe(true);
  });

  it('refuses a deduction with no description', () => {
    const result = freeLookSchema.safeParse({
      payeeRef: '+255700000009',
      deductions: [{ description: '', amount: '8000.00' }],
    });
    expect(result.success).toBe(false);
    expect(result.error?.issues[0]?.message).toBe('Every deduction needs a description');
  });

  it('refuses more than two decimal places, as the server does', () => {
    expect(
      freeLookSchema.safeParse({
        payeeRef: '+255700000009',
        deductions: [{ description: 'Medical', amount: '10.123' }],
      }).success,
    ).toBe(false);
  });

  it('refuses a NEGATIVE deduction, which would add to the refund', () => {
    // lib/money's AMOUNT_PATTERN allows a leading minus; this form must not, and neither does
    // the backend's @Pattern. The one direction this form cannot be allowed to move money.
    expect(
      freeLookSchema.safeParse({
        payeeRef: '+255700000009',
        deductions: [{ description: 'Medical', amount: '-8000.00' }],
      }).success,
    ).toBe(false);
  });
});
