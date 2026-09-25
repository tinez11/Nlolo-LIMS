import { describe, expect, it } from 'vitest';
import { shouldPin } from './railPin';

describe('shouldPin', () => {
  it('pins a rail that fits under the bar with room to spare', () => {
    expect(shouldPin(500, 900, 120)).toBe(true);
  });

  it('does not pin a rail taller than the space below the bar', () => {
    // Pinned, its last rows would be permanently out of reach -- the reason the rail grew its
    // own scrollbar, and the third scrollbar on the screen is what this replaces.
    expect(shouldPin(800, 900, 120)).toBe(false);
  });

  it('treats an unmeasured rail as not pinnable', () => {
    expect(shouldPin(0, 900, 120)).toBe(false);
  });
});
