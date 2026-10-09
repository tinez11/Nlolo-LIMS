import { beforeEach, describe, expect, it } from 'vitest';
import { forgetAll, remember, remembered } from './remembered';

describe('remembered', () => {
  beforeEach(() => forgetAll());

  it('returns nothing for a request never answered', () => {
    expect(remembered('schedule:POL-1')).toBeNull();
  });

  it('returns the last answer to a request, and the latest wins', () => {
    remember('schedule:POL-1', { lines: 1 });
    remember('schedule:POL-1', { lines: 2 });
    expect(remembered('schedule:POL-1')).toEqual({ lines: 2 });
  });

  it('keeps answers apart per request', () => {
    remember('schedule:POL-1', 'one');
    remember('schedule:POL-2', 'two');
    expect(remembered('schedule:POL-1')).toBe('one');
  });

  it('forgets the oldest answer past its limit, not the newest', () => {
    for (let i = 0; i < 51; i++) remember(`k${i}`, i);
    expect(remembered('k0')).toBeNull();
    expect(remembered('k50')).toBe(50);
  });
});
