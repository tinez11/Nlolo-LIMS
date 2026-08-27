import { describe, expect, it } from 'vitest';
import type { CoverageStatusView, PolicyView } from '@/api/types';
import { claimGates } from './claimGates';

/**
 * The point of the module: the rule set is a pure function of fetched data, so
 * it is exercised against fixtures with no DOM and no network.
 */

type Benefit = 'DEATH' | 'DISABILITY' | 'CRITICAL_ILLNESS' | 'MATURITY';

const policy = (over: Partial<PolicyView> = {}): PolicyView =>
  ({
    policyNumber: 'POL-12345678',
    status: 'ACTIVE',
    issueDate: '2024-01-15',
    ...over,
  }) as PolicyView;

const cover = (...benefits: Benefit[]): CoverageStatusView => ({
  policyNumber: 'POL-12345678',
  asOf: '2026-08-02',
  activeCoverages: benefits.map((benefitType) => ({
    benefitType,
    sumAssured: { amount: '30000000.00', currencyCode: 'TZS' },
  })),
});

const find = (gates: ReturnType<typeof claimGates>, fragment: string) =>
  gates.find((g) => g.title.includes(fragment));

describe('claimGates', () => {
  it('says nothing without a date of event', () => {
    expect(claimGates({ policy: policy(), coverage: cover('DEATH'), claimType: 'DEATH', dateOfEvent: null })).toEqual([]);
  });

  it('passes every gate for an active policy, in-term event, benefit on record', () => {
    const gates = claimGates({
      policy: policy(),
      coverage: cover('DEATH'),
      claimType: 'DEATH',
      dateOfEvent: '2026-08-02',
    });

    expect(gates).toHaveLength(3);
    expect(gates.every((g) => g.ok)).toBe(true);
  });

  describe('risk commencement — the one genuinely date-bounded check', () => {
    it('fails hard when the event predates issuance', () => {
      const gates = claimGates({
        policy: policy({ issueDate: '2024-01-15' }),
        coverage: cover('DEATH'),
        claimType: 'DEATH',
        dateOfEvent: '2001-01-01',
      });

      const gate = find(gates, 'Risk had commenced')!;
      expect(gate.ok).toBe(false);
      expect(gate.hard).toBe(true);
      expect(gate.detail).toContain('Jan 15, 2024');
    });

    it('treats the issue date itself as covered', () => {
      const gates = claimGates({
        policy: policy({ issueDate: '2024-01-15' }),
        coverage: cover('DEATH'),
        claimType: 'DEATH',
        dateOfEvent: '2024-01-15',
      });

      expect(find(gates, 'Risk had commenced')!.ok).toBe(true);
    });
  });

  describe('policy state — a flag, never a refusal', () => {
    it('flags a lapsed policy softly and says a person must verify', () => {
      const gates = claimGates({
        policy: policy({ status: 'LAPSED' }),
        coverage: cover('DEATH'),
        claimType: 'DEATH',
        dateOfEvent: '2026-08-02',
      });

      const gate = find(gates, 'can be read from the record')!;
      expect(gate.ok).toBe(false);
      // Soft: a lapsed policy routes a death claim to investigation. Auto-refusing
      // it without verifying the lapse notices were provably sent is the failure
      // this softness exists to prevent.
      expect(gate.hard).toBe(false);
      expect(gate.detail).toContain('does not refuse it');
    });

    it('does not flag a matured policy on a maturity claim', () => {
      const gates = claimGates({
        policy: policy({ status: 'MATURED' }),
        coverage: cover('MATURITY'),
        claimType: 'MATURITY',
        dateOfEvent: '2026-08-02',
      });

      expect(find(gates, 'can be read from the record')!.ok).toBe(true);
    });

    it('does flag a matured policy on a death claim', () => {
      const gates = claimGates({
        policy: policy({ status: 'MATURED' }),
        coverage: cover('DEATH'),
        claimType: 'DEATH',
        dateOfEvent: '2026-08-02',
      });

      expect(find(gates, 'can be read from the record')!.ok).toBe(false);
    });
  });

  describe('benefit on record', () => {
    it('catches a claim against a benefit the policy does not carry', () => {
      const gates = claimGates({
        policy: policy(),
        coverage: cover('DEATH'),
        claimType: 'CRITICAL_ILLNESS',
        dateOfEvent: '2026-08-02',
      });

      const gate = find(gates, 'critical illness cover')!;
      expect(gate.ok).toBe(false);
      expect(gate.hard).toBe(true);
      expect(gate.detail).toContain('DEATH');
    });

    it('never claims the benefit was in force on the day, only that it is on record', () => {
      const gates = claimGates({
        policy: policy(),
        coverage: cover('DEATH'),
        claimType: 'DEATH',
        dateOfEvent: '2026-08-02',
      });

      const gate = find(gates, 'death cover')!;
      expect(gate.detail).toContain('current record');
      expect(gate.detail).not.toContain('Aug 2, 2026');
    });

    it('treats an absent activeCoverages as no benefit rather than crashing', () => {
      const gates = claimGates({
        policy: policy(),
        coverage: { policyNumber: 'POL-12345678', asOf: '2026-08-02' },
        claimType: 'DEATH',
        dateOfEvent: '2026-08-02',
      });

      expect(find(gates, 'death cover')!.ok).toBe(false);
    });

    it('withholds the benefit gate until a claim type is chosen', () => {
      const gates = claimGates({
        policy: policy(),
        coverage: cover('DEATH'),
        claimType: null,
        dateOfEvent: '2026-08-02',
      });

      expect(find(gates, 'cover')).toBeUndefined();
      expect(gates).toHaveLength(2);
    });
  });

  it('offers what it can before the policy has loaded', () => {
    const gates = claimGates({
      policy: null,
      coverage: cover('DEATH'),
      claimType: 'DEATH',
      dateOfEvent: '2026-08-02',
    });

    // Only the benefit gate: both date and state checks need the policy.
    expect(gates).toHaveLength(1);
    expect(find(gates, 'death cover')).toBeDefined();
  });
});
