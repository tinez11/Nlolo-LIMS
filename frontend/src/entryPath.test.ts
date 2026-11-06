import { describe, expect, it } from 'vitest';
import { hasRealmFallback } from './entryPath';
import { REALM_HOME } from './screens';

describe('hasRealmFallback', () => {
  it('a realm with a home may redirect its unknown paths there', () => {
    expect(hasRealmFallback('policies')).toBe(true);
  });

  it('a realm with no home may not, or its own root would redirect to itself for ever', () => {
    expect(hasRealmFallback(null)).toBe(false);
  });

  it('regulators is the realm this protects, and still has no home', () => {
    // If a home is ever given to regulators this expectation fails, and the guard simply starts applying to it.
    expect(REALM_HOME.regulators).toBeNull();
    expect(hasRealmFallback(REALM_HOME.regulators)).toBe(false);
  });
});
