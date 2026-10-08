import { describe, expect, it, vi } from 'vitest';
import type { KeyboardEvent } from 'react';
import { matchCount, searchEnter } from './searchKeys';

const key = (k: string) => ({ key: k, preventDefault: vi.fn() }) as unknown as KeyboardEvent<HTMLInputElement>;

describe('search boxes inside forms', () => {
  it('turns Enter into "pick the single match" instead of submitting', () => {
    const pick = vi.fn();
    const enter = key('Enter');
    searchEnter(pick)(enter);
    expect(enter.preventDefault).toHaveBeenCalled();
    expect(pick).toHaveBeenCalledOnce();

    const letter = key('a');
    searchEnter(pick)(letter);
    expect(letter.preventDefault).not.toHaveBeenCalled();
    expect(pick).toHaveBeenCalledOnce();
  });

  it('says how many matched', () => {
    expect(matchCount(1, 'family', 'families')).toBe('1 family matches');
    expect(matchCount(3, 'family', 'families')).toBe('3 families match');
  });
});
