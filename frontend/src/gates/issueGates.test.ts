import { describe, expect, it } from 'vitest';
import type { AgentView, PartyDetailView, ProductSnapshot } from '@/api/types';
import { ageOn, hasHardFailure, issueGates, softBreaches } from './issueGates';

const TODAY = '2026-09-03';

const snapshot = (eligibility: ProductSnapshot['eligibility']): ProductSnapshot =>
  ({ productId: 'p', productVersionId: 'v', eligibility }) as ProductSnapshot;

const policyholder = (over: Partial<PartyDetailView> = {}): PartyDetailView =>
  ({ dateOfBirth: '1990-01-01', kycStatus: 'VERIFIED', ...over }) as PartyDetailView;

const agent = (over: Partial<AgentView> = {}): AgentView =>
  ({
    agentId: 'a',
    licenseNumber: 'LIC-1',
    licenseStatus: 'ACTIVE',
    licenseExpiryDate: '2030-01-01',
    ...over,
  }) as AgentView;

const base = {
  snapshot: snapshot({ minEntryAge: 18, maxEntryAge: 65, minTermMonths: 60, maxTermMonths: 360 }),
  policyholder: policyholder(),
  agent: null,
  commencementDate: '2026-10-01',
  policyTermMonths: 240,
  sumAssured: 2_000_000,
  today: TODAY,
};

const byTitle = (gates: ReturnType<typeof issueGates>, fragment: string) =>
  gates.find((g) => g.title.toLowerCase().includes(fragment.toLowerCase()));

describe('ageOn', () => {
  // Calendar arithmetic, not milliseconds: (now - dob) / 365.25 disagrees with
  // java.time.Period on leap years and on the birthday itself.
  it('counts whole years', () => {
    expect(ageOn('1990-01-01', '2026-01-01')).toBe(36);
  });

  it('does not count the birthday until it arrives', () => {
    expect(ageOn('1990-06-15', '2026-06-14')).toBe(35);
    expect(ageOn('1990-06-15', '2026-06-15')).toBe(36);
  });

  it('handles a 29 February birth date', () => {
    expect(ageOn('2000-02-29', '2026-02-28')).toBe(25);
    expect(ageOn('2000-02-29', '2026-03-01')).toBe(26);
  });
});

describe('entry age', () => {
  it('passes inside the bound', () => {
    expect(byTitle(issueGates(base), 'entry age')?.ok).toBe(true);
  });

  it('fails hard above the maximum, because no rate cell exists for that age', () => {
    const gates = issueGates({ ...base, policyholder: policyholder({ dateOfBirth: '1950-01-01' }) });
    const gate = byTitle(gates, 'entry age');
    expect(gate?.ok).toBe(false);
    expect(gate?.hard).toBe(true);
    expect(hasHardFailure(gates)).toBe(true);
  });

  it('fails hard below the minimum', () => {
    const gates = issueGates({ ...base, policyholder: policyholder({ dateOfBirth: '2015-01-01' }) });
    expect(byTitle(gates, 'entry age')?.ok).toBe(false);
  });

  // Entry age is the age at which cover STARTS. A proposal commencing next year
  // can cross a birthday before the risk begins, and the bound applies then.
  it('is computed at commencement, not today', () => {
    const turnsSixtySixBeforeCover = { ...base, policyholder: policyholder({ dateOfBirth: '1960-10-15' }) };
    // 65 today (3 Sep 2026), still 65 on 1 Oct 2026 -> inside the bound.
    expect(byTitle(issueGates(turnsSixtySixBeforeCover), 'entry age')?.ok).toBe(true);
    // Commencing after the birthday makes them 66 -> outside it.
    expect(
      byTitle(issueGates({ ...turnsSixtySixBeforeCover, commencementDate: '2026-11-01' }), 'entry age')?.ok,
    ).toBe(false);
  });

  it('produces no gate when the product sets no age bound', () => {
    const gates = issueGates({ ...base, snapshot: snapshot({}) });
    expect(byTitle(gates, 'entry age')).toBeUndefined();
  });

  it('produces no gate when no policyholder is chosen yet', () => {
    expect(byTitle(issueGates({ ...base, policyholder: null }), 'entry age')).toBeUndefined();
  });
});

describe('term', () => {
  it('fails hard outside the bound', () => {
    const gates = issueGates({ ...base, policyTermMonths: 480 });
    const gate = byTitle(gates, 'term');
    expect(gate?.ok).toBe(false);
    expect(gate?.hard).toBe(true);
  });

  it('accepts the boundary values themselves', () => {
    expect(byTitle(issueGates({ ...base, policyTermMonths: 60 }), 'term')?.ok).toBe(true);
    expect(byTitle(issueGates({ ...base, policyTermMonths: 360 }), 'term')?.ok).toBe(true);
  });

  // Whole life, an annuity and a renewable group scheme have no term at all.
  it('produces no gate when no term is being set', () => {
    expect(byTitle(issueGates({ ...base, policyTermMonths: null }), 'term')).toBeUndefined();
  });
});

describe('sum assured', () => {
  const bounded = {
    ...base,
    snapshot: snapshot({ minSumAssured: 500_000, maxSumAssured: 300_000_000 }),
  };

  // The whole business argument: above retention is cedeable, not invalid.
  it('flags rather than blocks above the maximum', () => {
    const gates = issueGates({ ...bounded, sumAssured: 500_000_000 });
    const gate = byTitle(gates, 'sum assured');
    expect(gate?.ok).toBe(false);
    expect(gate?.hard).toBe(false);
    expect(hasHardFailure(gates)).toBe(false);
    expect(softBreaches(gates)).toHaveLength(1);
    expect(gate?.detail).toMatch(/reason for issue/i);
  });

  it('flags below the minimum too', () => {
    expect(byTitle(issueGates({ ...bounded, sumAssured: 1000 }), 'sum assured')?.ok).toBe(false);
  });

  it('passes inside the range', () => {
    expect(byTitle(issueGates({ ...bounded, sumAssured: 2_000_000 }), 'sum assured')?.ok).toBe(true);
  });
});

describe('policyholder KYC', () => {
  // Soft on purpose: no backend rule refuses issuance to a PENDING party, so a
  // hard gate would be the UI inventing a refusal the platform does not make.
  it('flags a pending KYC without blocking', () => {
    const gates = issueGates({ ...base, policyholder: policyholder({ kycStatus: 'PENDING' }) });
    const gate = byTitle(gates, 'identity is verified');
    expect(gate?.ok).toBe(false);
    expect(gate?.hard).toBe(false);
  });

  it('says plainly when KYC was rejected', () => {
    const gates = issueGates({ ...base, policyholder: policyholder({ kycStatus: 'REJECTED' }) });
    expect(byTitle(gates, 'identity is verified')?.detail).toMatch(/REJECTED/);
  });
});

describe('agent licence', () => {
  // A direct sale names no agent. That is not a failure and must produce no gate,
  // or every direct policy would show a red panel.
  it('produces no gate at all when there is no agent', () => {
    const gates = issueGates(base);
    expect(byTitle(gates, 'licence')).toBeUndefined();
  });

  it('fails hard on a non-active licence', () => {
    const gates = issueGates({ ...base, agent: agent({ licenseStatus: 'SUSPENDED' }) });
    const gate = byTitle(gates, 'active licence');
    expect(gate?.ok).toBe(false);
    expect(gate?.hard).toBe(true);
  });

  // Valid today but expired by the date cover starts has not licensed this sale.
  it('checks expiry against commencement, not today', () => {
    const expiringSoon = agent({ licenseExpiryDate: '2026-09-30' });
    expect(
      byTitle(issueGates({ ...base, agent: expiringSoon, commencementDate: '2026-09-15' }), 'still valid')?.ok,
    ).toBe(true);
    expect(
      byTitle(issueGates({ ...base, agent: expiringSoon, commencementDate: '2026-10-01' }), 'still valid')?.ok,
    ).toBe(false);
  });

  it('passes an active licence expiring after cover starts', () => {
    const gates = issueGates({ ...base, agent: agent() });
    expect(byTitle(gates, 'active licence')?.ok).toBe(true);
    expect(byTitle(gates, 'still valid')?.ok).toBe(true);
  });
});

describe('the gate set as a whole', () => {
  it('is empty when a product states no bounds and nothing else is known', () => {
    const gates = issueGates({
      ...base,
      snapshot: snapshot({}),
      policyholder: null,
      agent: null,
      policyTermMonths: null,
      sumAssured: null,
    });
    expect(gates).toEqual([]);
  });

  it('separates hard failures from soft ones', () => {
    const gates = issueGates({
      ...base,
      snapshot: snapshot({ maxEntryAge: 65, maxSumAssured: 1_000_000 }),
      policyholder: policyholder({ dateOfBirth: '1950-01-01', kycStatus: 'PENDING' }),
      sumAssured: 5_000_000,
    });
    expect(hasHardFailure(gates)).toBe(true);
    // Sum assured and KYC, both soft.
    expect(softBreaches(gates)).toHaveLength(2);
  });
});
