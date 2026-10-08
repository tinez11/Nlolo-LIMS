import type { KeyboardEvent } from 'react';

/**
 * Enter in a search box that sits inside a form (2026-10-08). Left alone, Enter submits the form: staff
 * searching for a family on the claim screen pressed Enter and got "Choose who died" from a claim they had
 * not finished. Here Enter never submits; it picks the match when there is exactly one.
 */
export function searchEnter(pickSingleMatch?: () => void) {
  return (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    pickSingleMatch?.();
  };
}

/** "1 family matches" / "3 families match": what a search found, said under the box. */
export function matchCount(count: number, one: string, many: string): string {
  return count === 1 ? `1 ${one} matches` : `${count} ${many} match`;
}
