import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import type { PolicyBonusView } from '@/api/types';
import { success } from '@/store/createResourceSlice';
import { useBonusStore } from '@/store/bonusStore';
import { PolicyBonusesPanel } from './PolicyBonusesPanel';

const money = (amount: string) => ({ amount, currencyCode: 'TZS' });

const bonuses: PolicyBonusView = {
  policyNumber: 'POL-1',
  attachedTotal: money('0.00'),
  entries: [
    { entryId: 'e1', seq: 1, type: 'REVERSIONARY', amount: money('30000.00'), totalAfter: money('30000.00'),
      effectiveDate: '2026-12-31', declarationId: 'd1', basis: money('1000000.00'), ratePercent: '3',
      reversesEntryId: null, reason: null, createdBy: 'system:bonus-drain', createdAt: '2027-01-01T00:00:00Z' },
    { entryId: 'e2', seq: 2, type: 'REVERSAL', amount: money('-30000.00'), totalAfter: money('0.00'),
      effectiveDate: '2027-01-05', declarationId: null, basis: null, ratePercent: null,
      reversesEntryId: 'e1', reason: 'Cancelled in the free-look period', createdBy: 'staff-one', createdAt: '2027-01-05T00:00:00Z' },
  ],
  outcomes: [
    { declarationId: 'd2', valuationDate: '2027-12-31', outcome: 'NOT_ELIGIBLE', reason: 'Lapsed on the valuation date',
      decidedAt: '2028-01-01T00:00:00Z' },
    { declarationId: 'd1', valuationDate: '2026-12-31', outcome: 'ATTACHED', reason: null, decidedAt: '2027-01-01T00:00:00Z' },
  ],
  settlements: [
    { exitType: 'DEATH', exitRef: 'claim-1', exitDate: '2028-03-01',
      value: { attached: money('30000.00'), interim: money('1500.00'), terminal: money('15000.00'), total: money('46500.00'),
        interimRatePercent: '3', terminalRatePercent: '50', declarationId: 'd1' },
      recordedAt: '2028-03-02T00:00:00Z' },
  ],
};

/**
 * The Bonuses tab against a loaded store, with no network. Rendering it at all also guards the
 * Zustand render loop accountPanel's test describes: a selector returning a fresh object throws
 * "Maximum update depth exceeded" before any assertion runs.
 */
beforeEach(() => {
  useBonusStore.setState({ policy: { 'POL-1': success(bonuses) }, loadPolicy: async () => {} });
});

describe('PolicyBonusesPanel', () => {
  it('shows the ledger in sequence, a reversal naming what it reverses', () => {
    render(<PolicyBonusesPanel policyNumber="POL-1" />);
    const history = screen.getByRole('list', { name: 'Bonus history' });
    expect(history).toHaveTextContent('Reversionary bonus');
    expect(history).toHaveTextContent('3% of TZS 1,000,000.00');
    expect(history).toHaveTextContent('reverses #1');
  });

  it("says why a declaration did not attach, in the server's words", () => {
    render(<PolicyBonusesPanel policyNumber="POL-1" />);
    expect(screen.getByRole('list', { name: 'Declaration outcomes' })).toHaveTextContent('Lapsed on the valuation date');
  });

  it('shows what an exit paid, part by part', () => {
    render(<PolicyBonusesPanel policyNumber="POL-1" />);
    const paid = screen.getByRole('list', { name: 'Bonus settlements' });
    expect(paid).toHaveTextContent('Death claim');
    expect(paid).toHaveTextContent('TZS 46,500.00');
  });

  it('renders nothing for a policy that is not with-profits', () => {
    useBonusStore.setState({ policy: { 'POL-2': success(null) } });
    const { container } = render(<PolicyBonusesPanel policyNumber="POL-2" />);
    expect(container).toBeEmptyDOMElement();
  });
});
