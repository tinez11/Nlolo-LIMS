import { describe, expect, it } from 'vitest';
import { entryPath } from './entryPath';

describe('entryPath', () => {
  it('shows the realm picker in development', () => {
    expect(entryPath(true)).toBeNull();
  });

  it('opens the customer portal in production', () => {
    expect(entryPath(false)).toBe('/customers');
  });
});
