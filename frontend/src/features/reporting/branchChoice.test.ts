import { describe, expect, it } from 'vitest';
import { ALL_BRANCHES, branchOptions, branchWords, chosenBranch } from './branchChoice';

const REGISTER = [
  { code: 'ARU', name: 'Arusha', active: true, headOffice: false },
  { code: 'DSM', name: 'Dar es Salaam', active: true, headOffice: true },
  { code: 'MOS', name: 'Moshi', active: true, headOffice: false },
  { code: 'OLD', name: 'Closed branch', active: false, headOffice: false },
];

describe('chosenBranch (2026-11-23, branch views)', () => {
  it('starts on the home branch when it is not the head office', () => {
    expect(chosenBranch(null, 'ARU', 'DSM')).toBe('ARU');
  });

  it('starts on every branch for head-office staff', () => {
    expect(chosenBranch(null, 'DSM', 'DSM')).toBe(ALL_BRANCHES);
  });

  it('starts on every branch for someone with no home branch', () => {
    expect(chosenBranch(null, null, 'DSM')).toBe(ALL_BRANCHES);
  });

  it('starts on the home branch while the head office is not yet known', () => {
    expect(chosenBranch(null, 'ARU', null)).toBe('ARU');
  });

  it('follows the address over the home branch: all, or a named branch', () => {
    expect(chosenBranch('all', 'ARU', 'DSM')).toBe(ALL_BRANCHES);
    expect(chosenBranch('MOS', 'ARU', 'DSM')).toBe('MOS');
  });

  it('treats an empty address value as absent', () => {
    expect(chosenBranch('', 'ARU', 'DSM')).toBe('ARU');
  });
});

describe('branchOptions', () => {
  it('lists the open branches, head office first, marking your own', () => {
    expect(branchOptions(REGISTER, 'ARU')).toEqual([
      { code: 'DSM', name: 'Dar es Salaam', label: 'Dar es Salaam' },
      { code: 'ARU', name: 'Arusha', label: 'Arusha (your branch)' },
      { code: 'MOS', name: 'Moshi', label: 'Moshi' },
    ]);
  });

  it('marks nothing for head-office staff', () => {
    expect(branchOptions(REGISTER, 'DSM').map((o) => o.label)).toEqual(['Dar es Salaam', 'Arusha', 'Moshi']);
  });

  it('marks nothing for someone with no home branch', () => {
    expect(branchOptions(REGISTER, null).every((o) => !o.label.includes('your branch'))).toBe(true);
  });
});

describe('branchWords', () => {
  const options = branchOptions(REGISTER, 'ARU');

  it('names a branch by its name', () => {
    expect(branchWords('ARU', options)).toBe('Arusha branch');
  });

  it('says nothing for every branch', () => {
    expect(branchWords(ALL_BRANCHES, options)).toBeNull();
  });

  it('falls back to the code while the register loads', () => {
    expect(branchWords('ARU', [])).toBe('ARU branch');
  });
});
