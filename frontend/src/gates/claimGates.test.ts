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

  it('passes every gate for an active, in-force, no-term policy with the benefit on record', () => {
    const gates = claimGates({
      policy: policy(),
      coverage: cover('DEATH'),
      claimType: 'DEATH',
      dateOfEvent: '2026-08-02',
    });

    // Risk commenced + benefit on record. No maturity date on this policy, so no term gate; the
    // status is plainly in force, so no soft flag.
    expect(gates).toHaveLength(2);
    expect(gates.every((g) => g.ok)).toBe(true);
  });

  describe('risk commencement — genuinely date-bounded', () => {
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

  describe('the maturity window — proved from the one date PolicyView carries', () => {
    it('passes a non-maturity claim whose event falls within the term', () => {
      const gates = claimGates({
        policy: policy({ maturityDate: '2030-01-15' }),
        coverage: cover('DEATH'),
        claimType: 'DEATH',
        dateOfEvent: '2026-08-02',
      });

      const gate = find(gates, 'within the policy term')!;
      expect(gate.ok).toBe(true);
      expect(gate.hard).toBe(true);
    });

    it('fails a non-maturity claim hard once the term has ended', () => {
      const gates = claimGates({
        policy: policy({ status: 'EXPIRED', maturityDate: '2025-01-15' }),
        coverage: cover('DEATH'),
        claimType: 'DEATH',
        dateOfEvent: '2026-08-02',
      });

      const gate = find(gates, 'within the policy term')!;
      expect(gate.ok).toBe(false);
      expect(gate.hard).toBe(true);
      expect(gate.detail).toContain('Jan 15, 2025');
    });

    it('passes a maturity claim once the maturity date is reached', () => {
      const gates = claimGates({
        policy: policy({ status: 'MATURED', maturityDate: '2026-01-15' }),
        coverage: cover('MATURITY'),
        claimType: 'MATURITY',
        dateOfEvent: '2026-01-15',
      });

      const gate = find(gates, 'reached maturity')!;
      expect(gate.ok).toBe(true);
      expect(gate.hard).toBe(true);
    });

    it('fails a maturity claim hard before the maturity date', () => {
      const gates = claimGates({
        policy: policy({ maturityDate: '2030-01-15' }),
        coverage: cover('MATURITY'),
        claimType: 'MATURITY',
        dateOfEvent: '2026-08-02',
      });

      const gate = find(gates, 'reached maturity')!;
      expect(gate.ok).toBe(false);
      expect(gate.hard).toBe(true);
    });

    it('fails a maturity claim hard when the policy carries no maturity date at all', () => {
      const gates = claimGates({
        policy: policy(),
        coverage: cover('DEATH'),
        claimType: 'MATURITY',
        dateOfEvent: '2026-08-02',
      });

      const gate = find(gates, 'reached maturity')!;
      expect(gate.ok).toBe(false);
      expect(gate.hard).toBe(true);
      expect(gate.detail).toContain('no maturity date');
    });
  });

  describe('policy state — a soft flag, never a refusal', () => {
    it('flags a lapsed policy softly and defers the on-risk call to submit', () => {
      const gates = claimGates({
        policy: policy({ status: 'LAPSED' }),
        coverage: cover('DEATH'),
        claimType: 'DEATH',
        dateOfEvent: '2026-08-02',
      });

      const gate = find(gates, 'confirmed on submit')!;
      expect(gate.ok).toBe(false);
      // Soft: a lapsed policy routes a death claim to the server's date-bounded check, which the
      // backend now makes (Policy.wasOnRiskOn). Auto-refusing it here would pre-empt that.
      expect(gate.hard).toBe(false);
      expect(gate.detail).toContain('does not refuse it');
    });

    it('does not flag an active policy', () => {
      const gates = claimGates({
        policy: policy({ status: 'ACTIVE' }),
        coverage: cover('DEATH'),
        claimType: 'DEATH',
        dateOfEvent: '2026-08-02',
      });

      expect(find(gates, 'confirmed on submit')).toBeUndefined();
    });

    it('does not double-flag an expired policy — the term gate already speaks', () => {
      const gates = claimGates({
        policy: policy({ status: 'EXPIRED', maturityDate: '2025-01-15' }),
        coverage: cover('DEATH'),
        claimType: 'DEATH',
        dateOfEvent: '2026-08-02',
      });

      expect(find(gates, 'confirmed on submit')).toBeUndefined();
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

      expect(find(gates, ' cover')).toBeUndefined();
      // Risk-commencement gate only: no claim type, no maturity gate, no benefit gate.
      expect(gates).toHaveLength(1);
    });
  });

  it('offers what it can before the policy has loaded', () => {
    const gates = claimGates({
      policy: null,
      coverage: cover('DEATH'),
      claimType: 'DEATH',
      dateOfEvent: '2026-08-02',
    });

    // Only the benefit gate: risk, maturity and state all need the policy.
    expect(gates).toHaveLength(1);
    expect(find(gates, 'death cover')).toBeDefined();
  });
});
