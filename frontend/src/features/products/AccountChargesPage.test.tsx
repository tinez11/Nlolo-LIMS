import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import * as chargesApi from '@/api/accountCharges';
import type { AccountChargeView } from '@/api/accountCharges';
import { AccountChargesPage } from './AccountChargesPage';
import { chargeSize } from './chargeText';

vi.mock('@/api/accountCharges');
vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { access_token: 'token' } }) }));
vi.mock('@/auth/claims', () => ({
  readIdentity: () => ({}),
  staffRoles: () => ({ FINANCE_OFFICER: true, ADMIN: false }),
}));

const fee: AccountChargeView = {
  chargeId: 'c-1', name: 'Withdrawal fee', description: null, when: 'WITHDRAWAL', amountType: 'PERCENT', amount: 1.5,
  currency: 'TZS', active: true, createdBy: 'finance', createdAt: '2026-10-09T08:00:00Z',
};

describe('account charges', () => {
  it('says what a charge takes, flat or of what', () => {
    expect(chargeSize(fee)).toBe('1.5% of the amount withdrawn');
    expect(chargeSize({ ...fee, amountType: 'FLAT', amount: 1000, when: 'MONTHLY' })).toBe('TZS 1,000.00');
  });

  it('lists the charges, the minimum-balance rule, and creates a flat monthly fee', async () => {
    vi.mocked(chargesApi.listAccountCharges).mockResolvedValue([fee]);
    vi.mocked(chargesApi.getChargeSetting).mockResolvedValue({ mayGoBelowMinimum: false });
    vi.mocked(chargesApi.createAccountCharge).mockResolvedValue({ ...fee, chargeId: 'c-2' });
    render(<MemoryRouter><AccountChargesPage /></MemoryRouter>);

    expect(await screen.findByText('Withdrawal fee')).toBeInTheDocument();
    expect(screen.getByText(/Charges stop at the product’s minimum balance/)).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('Name'), 'Monthly fee');
    await userEvent.selectOptions(screen.getByLabelText('When it is taken'), 'MONTHLY');
    await userEvent.selectOptions(screen.getByLabelText('Type'), 'FLAT');
    await userEvent.type(screen.getByLabelText('Amount'), '1000');
    await userEvent.click(screen.getByRole('button', { name: 'Create charge' }));
    expect(chargesApi.createAccountCharge).toHaveBeenCalledWith({ name: 'Monthly fee', description: null, when: 'MONTHLY',
      amountType: 'FLAT', amount: 1000, currency: 'TZS' });
  });
});
