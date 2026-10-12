import { describe, expect, it } from 'vitest';
import { matchesReinsurer, treatyCedes, treatyTypeLabel } from './treatyTerms';

const retention = { amount: '57888888.00', currencyCode: 'TZS' };

describe('treatyTypeLabel', () => {
  it('reads as words, keeping XOL an acronym', () => {
    expect(treatyTypeLabel('QUOTA_SHARE')).toBe('Quota share');
    expect(treatyTypeLabel('SURPLUS')).toBe('Surplus');
    expect(treatyTypeLabel('XOL')).toBe('XOL');
  });
});

describe('treatyCedes', () => {
  it('describes a quota share by its percentage, never its placeholder retention', () => {
    expect(
      treatyCedes({ treatyType: 'QUOTA_SHARE', cessionPercent: '25.00', retentionLimit: { amount: '0.00', currencyCode: 'TZS' } }),
    ).toBe('25.00% of each policy');
  });

  it('describes a surplus and an XOL by their retention', () => {
    expect(treatyCedes({ treatyType: 'SURPLUS', cessionPercent: null, retentionLimit: retention })).toBe(
      'Cover above TZS 57,888,888.00',
    );
    expect(treatyCedes({ treatyType: 'XOL', cessionPercent: null, retentionLimit: retention })).toBe(
      'Losses above TZS 57,888,888.00',
    );
  });
});

describe('matchesReinsurer', () => {
  it('finds a reinsurer by any part of its name, ignoring case and surrounding spaces', () => {
    expect(matchesReinsurer({ reinsurerName: 'Martin Fredy' }, 'fredy')).toBe(true);
    expect(matchesReinsurer({ reinsurerName: 'Martin Fredy' }, '  MARTIN ')).toBe(true);
    expect(matchesReinsurer({ reinsurerName: 'Martin Fredy' }, 'Africa')).toBe(false);
  });

  it('matches everything on a blank query', () => {
    expect(matchesReinsurer({ reinsurerName: 'Africa Re' }, '   ')).toBe(true);
  });
});
