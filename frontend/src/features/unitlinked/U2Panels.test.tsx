import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useUnitLinkedStore } from '@/store/unitLinkedStore';
import { PremiumSplitPanel, WithdrawalPanel } from './U2Panels';

/**
 * A refinement on a whole list -- a split that does not total 100, named amounts that do not add up -- is filed by
 * react-hook-form under the array, not a field. These prove the server's words reach the screen and nothing is sent.
 */
describe('U2 panels show list-level refusals', () => {
  const redirect = vi.fn();
  const requestWithdrawal = vi.fn();

  beforeEach(() => {
    redirect.mockReset();
    requestWithdrawal.mockReset();
    useUnitLinkedStore.setState({ redirect, requestWithdrawal, splits: {}, withdrawals: {}, acting: {} });
  });

  it('refuses a premium split that does not total 100', async () => {
    render(<PremiumSplitPanel policyNumber="POL-1" fundCodes={['EQ1', 'BD1']} />);
    fireEvent.change(screen.getByLabelText('EQ1 (%)'), { target: { value: '60' } });
    fireEvent.change(screen.getByLabelText('BD1 (%)'), { target: { value: '30' } });
    fireEvent.click(screen.getByRole('button', { name: 'Redirect future premiums' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The fund split totals 90%; it must total 100%');
    expect(redirect).not.toHaveBeenCalled();
  });

  it('refuses named amounts that do not total the withdrawal', async () => {
    render(
      <WithdrawalPanel
        policyNumber="POL-1"
        heldFunds={['EQ1', 'BD1']}
        options={{ minimumWithdrawal: 100000, minimumRemainingValue: 500000, withdrawalReducesSumAssured: false, surrenderCharges: [] }}
        currency="TZS"
        viewerSubject="staff-one"
      />,
    );
    fireEvent.change(screen.getByLabelText('Gross amount (TZS)'), { target: { value: '150000' } });
    fireEvent.change(screen.getByLabelText('Pay to'), { target: { value: '+255700000600' } });
    fireEvent.change(screen.getByLabelText('From EQ1'), { target: { value: '100000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Request withdrawal' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      "The named funds' amounts total 100,000.00; they must total the withdrawal of 150,000.00",
    );
    expect(requestWithdrawal).not.toHaveBeenCalled();
  });
});
