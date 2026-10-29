import { describe, expect, it } from 'vitest';
import type { ActivityItem } from '@/api/dashboard';
import { describeActivity } from './activity';

const item = (eventType: string, refs: Record<string, string> = {}): ActivityItem =>
  ({ eventType, occurredAt: '2026-10-27T08:00:00Z', refs, amount: null }) as unknown as ActivityItem;

describe('describeActivity', () => {
  it('opens a payment on its policy in the payments register', () => {
    expect(describeActivity(item('payment.PaymentConfirmed', { policyNumber: 'POL-7' }))).toEqual({
      text: 'Payment confirmed',
      to: '/staff/payments?state=all&policy=POL-7',
    });
  });

  it('opens a payment with no policy on the state it ended in', () => {
    expect(describeActivity(item('payment.PaymentFailed')).to).toBe('/staff/payments?state=attention');
    expect(describeActivity(item('payment.DisbursementCompleted')).to).toBe('/staff/payments?state=done');
  });

  it('words an event it has never heard of from its own name, unlinked', () => {
    expect(describeActivity(item('payment.SomethingNewHappened'))).toEqual({ text: 'Something new happened' });
  });
});
