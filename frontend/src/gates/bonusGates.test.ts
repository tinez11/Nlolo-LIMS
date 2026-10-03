import { describe, expect, it } from 'vitest';
import type { BonusDeclarationView } from '@/api/types';
import { approveDeclarationGates } from './bonusGates';

const proposed = { declarationId: 'd1', status: 'PROPOSED', proposedBy: 'admin-one' } as BonusDeclarationView;

describe('approveDeclarationGates', () => {
  it('refuses the proposer in the server words', () => {
    const gates = approveDeclarationGates(proposed, 'admin-one');
    expect(gates.find((g) => !g.ok)?.detail).toBe(
      'A bonus declaration must be approved by someone other than the person who proposed it',
    );
  });
  it('lets a second person approve a proposal', () => {
    expect(approveDeclarationGates(proposed, 'finance-two').every((g) => g.ok)).toBe(true);
  });
  it('never treats an unknown viewer as the proposer', () => {
    expect(approveDeclarationGates(proposed, undefined).every((g) => g.ok)).toBe(true);
  });
  it('refuses anything but a proposal', () => {
    const gates = approveDeclarationGates({ ...proposed, status: 'APPROVED' }, 'finance-two');
    expect(gates.find((g) => !g.ok)?.detail).toBe('This bonus declaration is approved, not awaiting approval');
  });
});
