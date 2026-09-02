import { describe, expect, it } from 'vitest';
import {
  blankOriginateLoanForm,
  originateLoanFormSchema,
  toApiRequest,
} from './originateLoanForm';

describe('originateLoanForm', () => {
  it('accepts a whole-shilling amount and a payee reference', () => {
    const result = originateLoanFormSchema.safeParse({
      amount: '500000',
      payeeRef: 'MPESA-0712345678',
    });
    expect(result.success).toBe(true);
  });

  it('accepts exactly two decimal places', () => {
    expect(originateLoanFormSchema.safeParse({ amount: '500000.25', payeeRef: 'X' }).success).toBe(
      true,
    );
  });

  it.each(['500000.255', '500000.2555', '.5'])('rejects %s as an amount', (amount) => {
    // policy_loan.principal_amount is NUMERIC(19,2) -- a third decimal would be
    // silently rounded by Postgres, so it must fail where the user can see it.
    expect(originateLoanFormSchema.safeParse({ amount, payeeRef: 'X' }).success).toBe(false);
  });

  it.each(['0', '0.00'])('rejects %s -- there is no zero-value loan', (amount) => {
    expect(originateLoanFormSchema.safeParse({ amount, payeeRef: 'X' }).success).toBe(false);
  });

  it('rejects a negative amount', () => {
    // The minus sign fails the pattern outright. This is the client-side half of
    // the negative-amount defence the platform enforces at three other layers:
    // MoneyDto's @DecimalMin, PolicyApiImpl.reserveLoanValue's sign guard, and
    // PolicyAccount.increaseEncumbrance's own invariant -- a negative here would
    // otherwise REDUCE the encumbrance and raise available loan value.
    expect(originateLoanFormSchema.safeParse({ amount: '-1', payeeRef: 'X' }).success).toBe(false);
  });

  it('rejects a blank or whitespace-only payee reference', () => {
    expect(originateLoanFormSchema.safeParse({ amount: '1000', payeeRef: '' }).success).toBe(false);
    expect(originateLoanFormSchema.safeParse({ amount: '1000', payeeRef: '   ' }).success).toBe(
      false,
    );
  });

  it('does not reject a large amount, because only the server knows the ceiling', () => {
    // Available loan value is cash value net of encumbrance and live
    // reservations. This form cannot see any of that, and guessing would either
    // block a legitimate loan or race the server. The 409 carries the real number.
    expect(
      originateLoanFormSchema.safeParse({ amount: '999999999', payeeRef: 'X' }).success,
    ).toBe(true);
  });

  it('sends the amount as a decimal string, never a number', () => {
    const request = toApiRequest({ amount: '  500000.10  ', payeeRef: '  MPESA-1  ' });
    expect(request.requestedAmount.amount).toBe('500000.10');
    expect(typeof request.requestedAmount.amount).toBe('string');
    expect(request.requestedAmount.currencyCode).toBe('TZS');
    expect(request.payeeRef).toBe('MPESA-1');
  });

  it('starts blank', () => {
    expect(blankOriginateLoanForm()).toEqual({ amount: '', payeeRef: '' });
    expect(originateLoanFormSchema.safeParse(blankOriginateLoanForm()).success).toBe(false);
  });
});
