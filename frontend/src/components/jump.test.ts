import { describe, expect, it } from 'vitest';
import { resolveJump } from './jump';

describe('resolveJump', () => {
  it('opens an exact policy number, whatever the case and padding', () => {
    expect(resolveJump('  pol-7k2q9  ')).toBe('policies/POL-7K2Q9');
  });

  it('declines anything that is not an exact reference', () => {
    // No search endpoint exists, so the palette must not pretend a name is findable.
    expect(resolveJump('Juma')).toBeNull();
    expect(resolveJump('POL-')).toBeNull();
    expect(resolveJump('POL-12 34')).toBeNull();
  });
});
