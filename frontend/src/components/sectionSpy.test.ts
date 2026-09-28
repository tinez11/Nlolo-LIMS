import { describe, expect, it } from 'vitest';
import { activeSection } from './sectionSpy';

const TOPS = [
  { id: 'assessment', top: 0 },
  { id: 'evidence', top: 800 },
  { id: 'payment', top: 1600 },
];

describe('activeSection', () => {
  it('is the first section before anything has scrolled', () => {
    expect(activeSection(TOPS, 0, 100, false)).toBe('assessment');
  });

  it('is the section whose top has passed under the bars', () => {
    expect(activeSection(TOPS, 750, 100, false)).toBe('evidence');
  });

  it('does not advance until the next top clears the bars', () => {
    // 650 + 100 = 750, still short of evidence's 800. Without the offset this would
    // advance early and the bar would name a section still hidden behind the page bar.
    expect(activeSection(TOPS, 650, 100, false)).toBe('assessment');
  });

  it('is the last section at the bottom of the page, however short that section is', () => {
    // The whole reason atBottom is a parameter: a final panel shorter than the viewport
    // never reaches the top of it, so the loop alone can never mark it.
    expect(activeSection(TOPS, 900, 100, true)).toBe('payment');
  });

  it('is null when the page has no sections', () => {
    expect(activeSection([], 0, 100, false)).toBeNull();
  });
});
