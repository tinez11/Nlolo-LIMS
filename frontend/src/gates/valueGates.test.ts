import { describe, expect, it } from 'vitest';
import type { PolicyView, SurrenderQuote } from '@/api/types';
import { approveSurrenderGates, paidUpGates, surrenderGates } from './valueGates';

const policy = (over: Partial<PolicyView> = {}): PolicyView =>
  ({
    policyNumber: 'POL-1',
    status: 'ACTIVE',
    cashValue: { amount: '100000.00', currencyCode: 'TZS' },
    ...over,
  }) as PolicyView;

const quote = (amount: string): SurrenderQuote =>
  ({ policyNumber: 'POL-1', quotedValue: { amount, currencyCode: 'TZS' } }) as SurrenderQuote;

describe('surrenderGates', () => {
  it('passes an in-force policy with value and a positive quote', () => {
    expect(surrenderGates(policy(), quote('90000.00'), null).every((g) => g.ok)).toBe(true);
  });

  it('refuses a status the server refuses, and lists the ones it accepts', () => {
    const gates = surrenderGates(policy({ status: 'SURRENDERED' }), null, null);
    const failed = gates.find((g) => !g.ok)!;
    expect(failed.hard).toBe(true);
    expect(failed.detail).toContain('Surrendered');
  });

  // PAID_UP and LAPSED both surrender -- Policy.canSurrender -- and a gate that refused
  // them would be stricter than the platform it fronts.
  it('accepts a paid-up and a lapsed policy', () => {
    for (const status of ['PAID_UP', 'LAPSED'] as const) {
      expect(surrenderGates(policy({ status }), quote('90000.00'), null).every((g) => g.ok)).toBe(true);
    }
  });

  it('refuses a policy with no cash value', () => {
    const gates = surrenderGates(policy({ cashValue: { amount: '0.00', currencyCode: 'TZS' } }), quote('0.00'), null);
    expect(gates.some((g) => !g.ok && g.hard)).toBe(true);
  });

  // Product step 4: the server lets a policy surrender on its attached bonuses alone.
  it('accepts a policy whose only surrender value is its attached bonuses', () => {
    const bonusOnly = {
      ...quote('12000.00'),
      bonusSurrenderValue: { amount: '12000.00', currencyCode: 'TZS' },
    } as SurrenderQuote;
    const gates = surrenderGates(policy({ cashValue: { amount: '0.00', currencyCode: 'TZS' } }), bonusOnly, null);
    expect(gates.every((g) => g.ok)).toBe(true);
    expect(gates[1]!.detail).toBe('No cash value, but its attached bonuses are worth TZS 12,000.00 on surrender.');
  });

  it('refuses a quote that is zero after charges, naming the figure', () => {
    const gates = surrenderGates(policy(), quote('0.00'), null);
    const failed = gates.find((g) => !g.ok)!;
    expect(failed.detail).toContain('TZS 0.00');
  });

  it('refuses a second request while one is in flight', () => {
    for (const status of ['REQUESTED', 'APPROVED'] as const) {
      const gates = surrenderGates(policy(), quote('90000.00'), { status } as never);
      expect(gates.some((g) => !g.ok && g.hard && g.title === 'No surrender in flight')).toBe(true);
    }
  });

  it('allows a new request after an earlier one failed', () => {
    expect(surrenderGates(policy(), quote('90000.00'), { status: 'FAILED' } as never).every((g) => g.ok)).toBe(true);
  });
});

describe('approveSurrenderGates', () => {
  it('refuses the requester, in the server wording', () => {
    const gates = approveSurrenderGates({ status: 'REQUESTED', requestedBy: 'alice' } as never, 'alice');
    const failed = gates.find((g) => !g.ok)!;
    expect(failed.hard).toBe(true);
    expect(failed.detail).toContain('other than the person who requested it');
  });

  it('passes a different approver on a REQUESTED surrender', () => {
    expect(approveSurrenderGates({ status: 'REQUESTED', requestedBy: 'alice' } as never, 'bob').every((g) => g.ok))
      .toBe(true);
  });

  it('refuses a request that is not awaiting approval', () => {
    const gates = approveSurrenderGates({ status: 'APPROVED', requestedBy: 'alice' } as never, 'bob');
    expect(gates.some((g) => !g.ok && g.hard)).toBe(true);
  });
});

describe('paidUpGates', () => {
  it('passes an active policy with value', () => {
    expect(paidUpGates(policy()).every((g) => g.ok)).toBe(true);
  });

  it('refuses a status outside ACTIVE, REINSTATED and LAPSED', () => {
    const gates = paidUpGates(policy({ status: 'SUSPENDED' }));
    expect(gates.some((g) => !g.ok && g.hard)).toBe(true);
  });

  it('refuses a policy with no value yet', () => {
    const gates = paidUpGates(policy({ cashValue: { amount: '0.00', currencyCode: 'TZS' } }));
    expect(gates.some((g) => !g.ok && g.hard)).toBe(true);
  });

  /*
    The minimum-years rule is the server's alone: nothing on PolicyView carries premiums paid
    or the product's minimum, so a gate claiming to know it would be asserting what the console
    cannot prove. It is a SOFT note instead -- the submit is what finds out.
  */
  it('notes the minimum-years rule softly, never as a refusal', () => {
    const note = paidUpGates(policy()).find((g) => g.title === 'Minimum years is checked on submit')!;
    expect(note.hard).toBe(false);
    expect(note.ok).toBe(true);
  });
});
