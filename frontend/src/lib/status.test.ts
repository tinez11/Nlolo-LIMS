import { describe, expect, it } from 'vitest';
import { humanizeStatus, resolveStatus, STATUS_MAPS, type StatusKind } from './status';

const bucket = (kind: StatusKind, value: string) => resolveStatus(kind, value).bucket;

describe('resolveStatus', () => {
  // These seven were ratified explicitly because guessing them wrong misinforms
  // staff about money or coverage, not just about colour.
  describe('the ratified ambiguous mappings', () => {
    it('IN_DOUBT is a warning, not a failure -- the outcome is unknown, not lost', () => {
      expect(bucket('payment', 'IN_DOUBT')).toBe('warning');
      expect(bucket('payment', 'FAILED')).toBe('danger');
    });

    it('LOADED is a success -- it is an acceptance with a premium loading', () => {
      expect(bucket('underwritingDecision', 'LOADED')).toBe('success');
      expect(bucket('underwritingDecision', 'ACCEPT')).toBe('success');
    });

    it('POSTPONED is a warning -- a soft decline, not a queue state', () => {
      expect(bucket('underwritingDecision', 'POSTPONED')).toBe('warning');
    });

    it('FORCED_LAPSE_TRIGGERED is a danger', () => {
      expect(bucket('loan', 'FORCED_LAPSE_TRIGGERED')).toBe('danger');
    });

    it('WAIVED is neutral -- closed, but not by payment', () => {
      expect(bucket('invoice', 'WAIVED')).toBe('neutral');
      expect(bucket('invoice', 'PAID')).toBe('success');
    });

    it('IN_GRACE is a warning', () => {
      expect(bucket('invoice', 'IN_GRACE')).toBe('warning');
    });

    it('REOPENED is pending -- it is back in the queue', () => {
      expect(bucket('claim', 'REOPENED')).toBe('pending');
    });
  });

  // The reason this map is keyed by domain rather than by literal.
  it('resolves the same literal differently per domain where the domain differs', () => {
    // An underwriting case that is OPEN is waiting for someone.
    expect(bucket('underwritingCase', 'OPEN')).toBe('pending');
    // A commission statement that is OPEN is actively accruing -- it is the live state.
    expect(bucket('commissionStatement', 'OPEN')).toBe('active');
  });

  it('treats a lapsed policy as danger and a surrendered one as neutral', () => {
    expect(bucket('policy', 'LAPSED')).toBe('danger');
    expect(bucket('policy', 'SURRENDERED')).toBe('neutral');
    expect(bucket('policy', 'MATURED')).toBe('success');
    expect(bucket('policy', 'ACTIVE')).toBe('active');
  });

  it('marks a known status as known', () => {
    expect(resolveStatus('policy', 'ACTIVE')).toEqual({ bucket: 'active', known: true });
  });

  // A status the backend adds later must not be indistinguishable from a real
  // neutral one -- silent sentinels are how drift goes unnoticed.
  it('flags an unrecognised status instead of silently absorbing it', () => {
    expect(resolveStatus('policy', 'TIME_TRAVELLED')).toEqual({
      bucket: 'neutral',
      known: false,
    });
  });

  it('is case-sensitive -- the backend emits SCREAMING_SNAKE and nothing else', () => {
    expect(resolveStatus('policy', 'active').known).toBe(false);
  });
});

describe('STATUS_MAPS coverage', () => {
  it('maps every literal to one of the six buckets and nothing else', () => {
    const allowed = new Set(['neutral', 'pending', 'active', 'success', 'warning', 'danger']);
    for (const [kind, map] of Object.entries(STATUS_MAPS)) {
      for (const [literal, value] of Object.entries(map)) {
        expect(allowed.has(value), `${kind}.${literal} -> ${value}`).toBe(true);
      }
    }
  });

  it('uses SCREAMING_SNAKE_CASE literals throughout, matching the Java enums', () => {
    for (const [kind, map] of Object.entries(STATUS_MAPS)) {
      for (const literal of Object.keys(map)) {
        expect(literal, `${kind}.${literal}`).toMatch(/^[A-Z][A-Z0-9_]*$/);
      }
    }
  });

  // Guards against a half-added domain: every kind must actually have entries.
  it('has no empty domain', () => {
    for (const [kind, map] of Object.entries(STATUS_MAPS)) {
      expect(Object.keys(map).length, kind).toBeGreaterThan(0);
    }
  });

  it('covers the policy lifecycle exactly as PolicyStatus.java declares it', () => {
    expect(Object.keys(STATUS_MAPS.policy).sort()).toEqual(
      [
        'ACTIVE',
        'LAPSED',
        'MATURED',
        // An offer that expired unpaid. This test is what caught its absence when the status was
        // added backend-side, which is the job it exists to do.
        'NOT_TAKEN_UP',
        // A term policy that ran its full term and paid nothing (product step 0).
        'EXPIRED',
        'PROPOSED',
        'REINSTATED',
        'SURRENDERED',
        'SUSPENDED',
      ].sort(),
    );
  });

  it('covers the claim lifecycle exactly as ClaimStatus.java declares it', () => {
    expect(Object.keys(STATUS_MAPS.claim).sort()).toEqual(
      [
        'APPROVED',
        'REGISTERED',
        'REJECTED',
        'REOPENED',
        'SETTLED',
        'SETTLEMENT_REQUESTED',
        'UNDER_ASSESSMENT',
      ].sort(),
    );
  });

  it('covers the loan lifecycle exactly as LoanStatus.java declares it', () => {
    expect(Object.keys(STATUS_MAPS.loan).sort()).toEqual(
      [
        'DISBURSED',
        'DISBURSEMENT_FAILED',
        'DISBURSEMENT_REQUESTED',
        'FORCED_LAPSE_TRIGGERED',
        'ORIGINATED',
        'REPAYING',
        'RESERVED_PENDING_ORIGINATION',
        'SETTLED',
      ].sort(),
    );
  });

  it('covers the invoice lifecycle exactly as InvoiceStatus.java declares it', () => {
    expect(Object.keys(STATUS_MAPS.invoice).sort()).toEqual(
      ['DUE', 'IN_GRACE', 'OVERDUE', 'PAID', 'PARTIALLY_PAID', 'WAIVED'].sort(),
    );
  });

  it('covers account status exactly as AccountStatus.java declares it', () => {
    expect(Object.keys(STATUS_MAPS.account).sort()).toEqual(['ACTIVE', 'INACTIVE'].sort());
    expect(bucket('account', 'ACTIVE')).toBe('active');
    // Retiring an account is housekeeping, not a failure -- neutral, never danger.
    expect(bucket('account', 'INACTIVE')).toBe('neutral');
  });
});

describe('humanizeStatus', () => {
  it('sentence-cases an ordinary literal', () => {
    expect(humanizeStatus('SETTLEMENT_REQUESTED')).toBe('Settlement requested');
  });

  it('keeps the acronyms staff read as acronyms', () => {
    // Every one of these is a real literal in the generated API types, not an invented
    // example: a free cover limit, a reinsurance basis, and a ledger side are never
    // written in lower case anywhere in the business.
    expect(humanizeStatus('WITHIN_FCL')).toBe('Within FCL');
    expect(humanizeStatus('NATIONAL_ID')).toBe('National ID');
    expect(humanizeStatus('VOTER_ID')).toBe('Voter ID');
    expect(humanizeStatus('XOL')).toBe('XOL');
    expect(humanizeStatus('PAA')).toBe('PAA');
    expect(humanizeStatus('CR')).toBe('CR');
    expect(humanizeStatus('SMS')).toBe('SMS');
  });

  it('does not uppercase an acronym hiding inside a longer word', () => {
    expect(humanizeStatus('GLOBAL')).toBe('Global');
    expect(humanizeStatus('IDENTITY_VERIFIED')).toBe('Identity verified');
  });
});
