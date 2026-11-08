import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as reinsuranceApi from '@/api/reinsurance';
import type { ReinsuranceSettlement, ReinsuranceSettlementsOpen } from '@/api/types';
import { grantAccess } from '@/test/access';
import { renderScreen as render } from '@/test/renderScreen';
import { StatementSettlementPanel } from './StatementSettlementPanel';

vi.mock('@/api/reinsurance');
vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { access_token: 'token' } }) }));
vi.mock('@/auth/claims', () => ({ readIdentity: () => ({ subject: 'finance-two' }), canSeeFinance: () => true }));

const TZS = (amount: string) => ({ amount, currencyCode: 'TZS' });

const open = (over: Partial<ReinsuranceSettlementsOpen> = {}): ReinsuranceSettlementsOpen => ({
  statementStatus: 'APPROVED', theyOweUs: TZS('0.00'), weOweThem: TZS('120000.00'), profitCommissionDue: TZS('5000.00'),
  withheldHeld: TZS('0.00'), settlements: [], ...over,
});

const waiting: ReinsuranceSettlement = {
  settlementId: 's-1', statementId: 'st-1', treatyId: 't-1', reinsurerName: 'Africa Re', quarter: '2026-Q1',
  kind: 'PAID', amount: TZS('120000.00'), payeeBank: 'CRDB', payeeAccountName: 'Africa Re',
  payeeAccountNumber: '0150123456789', status: 'AWAITING_APPROVAL', recordedBy: 'finance-one',
  recordedAt: '2026-11-08T08:00:00Z', actedAlone: false,
} as ReinsuranceSettlement;

/** 2026-11-08: what an approved statement left open had no way out but a free-form manual journal. */
describe('StatementSettlementPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('offers only what is open, and pays the reinsurer to its bank account, never beyond what is owed', async () => {
    grantAccess('reinsurance.view', 'reinsurance.manage');
    vi.mocked(reinsuranceApi.getSettlements).mockResolvedValue(open());
    vi.mocked(reinsuranceApi.recordSettlement).mockResolvedValue(waiting);
    const user = userEvent.setup();
    render(<StatementSettlementPanel statementId="st-1" />);

    const kinds = await screen.findByLabelText('What is it?');
    expect([...kinds.querySelectorAll('option')].map((o) => o.value)).toEqual(['PAID', 'PROFIT_COMMISSION']);
    await user.type(screen.getByLabelText(/^Amount/), '120000.01');
    await user.click(screen.getByRole('button', { name: 'Record' }));
    expect(await screen.findByText('At most TZS 120,000.00 on this statement')).toBeInTheDocument();
    expect(reinsuranceApi.recordSettlement).not.toHaveBeenCalled();

    await user.clear(screen.getByLabelText(/^Amount/));
    await user.type(screen.getByLabelText(/^Amount/), '120000');
    await user.type(screen.getByLabelText("Reinsurer's bank"), 'CRDB');
    await user.type(screen.getByLabelText('Name on the account'), 'Africa Re');
    await user.type(screen.getByLabelText('Account number'), '0150123456789');
    await user.click(screen.getByRole('button', { name: 'Record' }));
    await waitFor(() => expect(reinsuranceApi.recordSettlement).toHaveBeenCalledWith('st-1', expect.objectContaining({
      kind: 'PAID', amount: '120000.00', payeeBank: 'CRDB', payeeAccountNumber: '0150123456789',
    })));
  });

  it('lets a second person confirm a payment, which goes to Payments to make', async () => {
    grantAccess('reinsurance.view', 'reinsurance.approve');
    vi.mocked(reinsuranceApi.getSettlements).mockResolvedValue(open({ settlements: [waiting] }));
    vi.mocked(reinsuranceApi.approveSettlement).mockResolvedValue({ ...waiting, status: 'REQUESTED' });
    const user = userEvent.setup();
    render(<StatementSettlementPanel statementId="st-1" />);

    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await user.click(screen.getByRole('button', { name: 'Send to Payments to make' }));
    expect(reinsuranceApi.approveSettlement).toHaveBeenCalledWith('s-1');
  });

  it('says so when nothing is left open', async () => {
    grantAccess('reinsurance.view', 'reinsurance.manage');
    vi.mocked(reinsuranceApi.getSettlements).mockResolvedValue(open({ weOweThem: TZS('0.00'), profitCommissionDue: TZS('0.00') }));
    render(<StatementSettlementPanel statementId="st-1" />);
    expect(await screen.findByText('Nothing is left open on this statement.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Record' })).not.toBeInTheDocument();
  });
});
