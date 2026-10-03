import { describe, expect, it } from 'vitest';
import type { WithholdingRuleView } from '@/api/types';
import { approveRuleGates } from './withholdingGates';

const proposed = { ruleId: 'r1', status: 'PROPOSED', proposedBy: 'finance-one' } as WithholdingRuleView;

describe('approveRuleGates', () => {
  it('refuses the proposer in the server words', () => {
    const gates = approveRuleGates(proposed, 'finance-one');
    expect(gates.find((g) => !g.ok)?.detail).toBe(
      'A withholding rule must be approved by someone other than the person who proposed it',
    );
  });
  it('lets a second person approve a proposal', () => {
    expect(approveRuleGates(proposed, 'finance-two').every((g) => g.ok)).toBe(true);
  });
  it('never treats an unknown viewer as the proposer', () => {
    expect(approveRuleGates(proposed, undefined).every((g) => g.ok)).toBe(true);
  });
  it('refuses anything but a proposal', () => {
    const gates = approveRuleGates({ ...proposed, status: 'APPROVED' }, 'finance-two');
    expect(gates.find((g) => !g.ok)?.detail).toBe('This withholding rule is approved, not awaiting approval');
  });
});
