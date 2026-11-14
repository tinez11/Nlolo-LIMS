import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as finApi from '@/api/finaccounting';
import type { CapitalView, ShareClassView, ShareholderView, ShareIssueView } from '@/api/types';
import { grantAccess } from '@/test/access';
import { renderScreen as render } from '@/test/renderScreen';
import { ShareholdersPage } from './ShareholdersPage';

vi.mock('@/api/finaccounting');
vi.mock('@/api/access');
vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { access_token: 'token' } }) }));
vi.mock('@/auth/claims', () => ({ readIdentity: () => ({ subject: 'finance-two' }), canSeeFinance: () => true }));

const capital: CapitalView = {
  paidUpCapital: 1_000_000_000, ledgerCapital: 1_000_000_000, issuedShares: 1_000_000, minimumPaidUp: null,
  minimumSetBy: null, minimumSetAt: null,
  books: { state: 'MET', figure: null },
  tanzanian: { state: 'MET', figure: '40.0%' },
  minimum: { state: 'NOT_SET', figure: null },
};

const holders: ShareholderView[] = [
  { shareholderId: 'h-1', name: 'Demo Holdings Ltd', kind: 'COMPANY', tanzanian: true, idNumber: 'REG-1', phone: null,
    address: null, shares: 400_000, paid: 400_000_000 },
  { shareholderId: 'h-2', name: 'Kilimo Partners', kind: 'COMPANY', tanzanian: false, idNumber: 'REG-2', phone: null,
    address: null, shares: 600_000, paid: 600_000_000 },
];

const ordinary: ShareClassView = { classCode: 'ORDINARY', nominalValue: 1000, setBy: 'finance-one',
  setAt: '2026-11-14T08:00:00Z', issued: true };

const issue = (over: Partial<ShareIssueView> = {}): ShareIssueView => ({
  issueId: 'i-1', shareholderId: 'h-1', shareholderName: 'Demo Holdings Ltd', classCode: 'ORDINARY', shares: 10,
  pricePerShare: 1500, nominalValue: 1000, amountPaid: 15_000, shareCapital: 10_000, sharePremium: 5_000,
  receivedOn: '2026-11-14', bankAccount: '1110', reference: 'Board resolution 1/2026', status: 'PROPOSED',
  proposedBy: 'finance-one', proposedAt: '2026-11-14T09:00:00Z', decidedBy: null, decidedAt: null,
  decisionReason: null, journalEntryId: null, actedAlone: false, ...over,
});

function load(issues: ShareIssueView[] = [], classes: ShareClassView[] = [ordinary]) {
  vi.mocked(finApi.getCapital).mockResolvedValue(capital);
  vi.mocked(finApi.listShareholders).mockResolvedValue(holders);
  vi.mocked(finApi.listShareClasses).mockResolvedValue(classes);
  vi.mocked(finApi.listShareIssues).mockResolvedValue(issues);
  vi.mocked(finApi.listShareBankAccounts).mockResolvedValue([{ code: '1110', name: 'Main operating bank account' }]);
}

describe('ShareholdersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('lights the paid-up capital and says each check once', async () => {
    grantAccess('ledger.view');
    load();
    render(<ShareholdersPage />);
    expect(await screen.findByText('TZS 1,000,000,000.00')).toBeInTheDocument();
    const checks = screen.getByText('Register agrees with the books').closest('ul')!;
    expect(within(checks).getAllByRole('listitem')).toHaveLength(3);
    expect(within(checks).getByText('Held 40.0%')).toBeInTheDocument();
    expect(within(checks).getAllByText('Met')).toHaveLength(2);
    expect(within(checks).getByText('Not set')).toBeInTheDocument();
  });

  it('lists each shareholder with the share of the issued shares they hold', async () => {
    grantAccess('ledger.view');
    load();
    render(<ShareholdersPage />);
    const row = (await screen.findByText('Kilimo Partners')).closest('tr')!;
    expect(within(row).getByText('Company · Foreign')).toBeInTheDocument();
    expect(within(row).getByText('60.0%')).toBeInTheDocument();
    expect(within(row).getByText('600,000')).toBeInTheDocument();
  });

  it('lets a second person approve or reject an issue someone else recorded', async () => {
    grantAccess('ledger.view', 'ledger.approve');
    load([issue()]);
    vi.mocked(finApi.approveShareIssue).mockResolvedValue(issue({ status: 'APPROVED', journalEntryId: 'j-1' }));
    const user = userEvent.setup();
    render(<ShareholdersPage />);
    await screen.findByText('Waiting for approval');
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Withdraw' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Approve and post' }));
    expect(finApi.approveShareIssue).not.toHaveBeenCalled();
    expect(screen.getByText(/Debit 1110 TZS 15,000.00; credit share capital TZS 10,000.00/)).toHaveTextContent(
      'and share premium TZS 5,000.00, in November 2026.',
    );
    await user.click(screen.getByRole('button', { name: 'Post to the ledger' }));
    expect(finApi.approveShareIssue).toHaveBeenCalledWith('i-1');
  });

  it('lets the recorder withdraw their own issue but not approve it alone', async () => {
    grantAccess('ledger.view', 'ledger.manage', 'ledger.approve');
    load([issue({ proposedBy: 'finance-two' })]);
    render(<ShareholdersPage />);
    await screen.findByText('Waiting for approval');
    expect(screen.getByRole('button', { name: 'Approve and post' })).toBeDisabled();
    expect(screen.getByText('You recorded it, so someone else must approve it.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Withdraw' })).toBeEnabled();
  });

  it('shows the capital and premium split while an issue is typed', async () => {
    grantAccess('ledger.view', 'ledger.manage');
    load();
    const user = userEvent.setup();
    render(<ShareholdersPage />);
    await user.click(await screen.findByRole('button', { name: 'Record an issue' }));
    await user.type(screen.getByLabelText(/^Number of shares/), '1000000');
    await user.type(screen.getByLabelText(/^Price per share/), '1500');
    expect(screen.getByText(/Share premium/).closest('p')).toHaveTextContent(
      'Share capital TZS 1,000,000,000.00 · Share premium TZS 500,000,000.00',
    );
  });

  it('asks for the nominal value before any issue, and hides the actions from a viewer', async () => {
    grantAccess('ledger.view', 'ledger.manage');
    load([], []);
    render(<ShareholdersPage />);
    expect(await screen.findByText('Set the nominal value of ordinary shares')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Record an issue' })).toBeDisabled();
  });

  it('shows no recording actions without ledger.manage', async () => {
    grantAccess('ledger.view');
    load();
    render(<ShareholdersPage />);
    await screen.findByText('Demo Holdings Ltd');
    expect(screen.queryByRole('button', { name: 'Add shareholder' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Record an issue' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /minimum/ })).not.toBeInTheDocument();
  });
});
