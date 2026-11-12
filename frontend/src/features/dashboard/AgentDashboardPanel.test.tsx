import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getAgentDashboard } from '@/api/dashboard';
import { getOwnAgent, getOwnTeam } from '@/api/distribution';
import { listMyFollowUp } from '@/api/followUp';
import { getNeedsAction } from '@/api/proposalAmendments';
import { useDashboardStore } from '@/store/dashboardStore';
import { useDistributionStore } from '@/store/distributionStore';
import { idle } from '@/store/createResourceSlice';
import { renderScreen } from '@/test/renderScreen';
import { AgentDashboardPanel } from './AgentDashboardPanel';

vi.mock('@/api/dashboard');
vi.mock('@/api/distribution');
vi.mock('@/api/followUp');
vi.mock('@/api/proposalAmendments');

const TZS = (amount: string) => ({ amount, currencyCode: 'TZS' });
const RANGE = { from: '2026-07', to: '2026-09' };
const VIEW = {
  clients: 309,
  clientsAwaitingKyc: 23,
  clientsKycRejected: 1,
  policiesInBook: 412,
  applicationsInUnderwriting: 5,
  commissionPeriod: '2026-09',
  commissionEarned: TZS('1284500.00'),
  commissionStatus: 'CLOSED',
  awaitingKyc: [{ partyId: 'party-1', displayName: 'Amina Hassan', kycStatus: 'PENDING' as const }],
  period: { from: '2026-07', to: '2026-09', compareFrom: '2025-07', compareTo: '2025-09', currentMonth: '2026-10' },
  commissionInPeriod: TZS('1284500.00'),
  commissionSeries: [
    { month: '2026-07', paid: TZS('1000000.00'), pending: TZS('0.00') },
    { month: '2026-08', paid: TZS('0.00'), pending: TZS('0.00') },
    { month: '2026-09', paid: TZS('0.00'), pending: TZS('284500.00') },
  ],
  sold: 3,
  soldBefore: 1,
  soldChangePercent: '200.0',
  soldSeries: [{ month: '2026-07', count: 2 }, { month: '2026-08', count: 0 }, { month: '2026-09', count: 1 }],
  bookByProduct: [
    { key: 'TERM_LIFE', amount: null, count: 300, percent: '72.8' },
    { key: 'FUNERAL', amount: null, count: 112, percent: '27.2' },
  ],
  premiumSold: TZS('1200000.00'),
  premiumSoldBefore: TZS('600000.00'),
  premiumSoldChangePercent: '100.0',
  singlePremiumsSold: TZS('500000.00'),
  coverSold: TZS('2000000.00'),
  savingsDepositsTaken: TZS('20000.00'),
  customersPaid: {
    from: '2026-11-01', to: '2026-11-12', due: 400000, paid: 300000, ratePercent: '75.0',
    dueTodayCount: 0, dueTodayOwed: 0, overdueCount: 1, overdueOwed: 100000, currency: 'TZS',
  },
};

const ME = { agentId: 'agent-me', partyId: 'party-me' };

beforeEach(() => {
  useDashboardStore.setState({ agent: { status: 'idle', data: null, error: null, loadedAt: null } } as never);
  useDistributionStore.setState({ ownAgent: idle(), ownTeam: idle() } as never);
  vi.mocked(getAgentDashboard).mockResolvedValue(VIEW);
  vi.mocked(getOwnAgent).mockResolvedValue(ME as never);
  vi.mocked(getOwnTeam).mockResolvedValue([ME] as never);
  vi.mocked(getNeedsAction).mockResolvedValue([]);
  vi.mocked(listMyFollowUp).mockResolvedValue({
    rows: [{
      invoiceId: 'inv-1', policyNumber: 'POL-1', clientName: 'Neema Paid', clientPhone: '+255712345678',
      productName: 'Term life', agentId: null, agentName: null, branchCode: null, owed: 240000, currency: 'TZS',
      dueDate: '2026-10-10', daysLate: 32, lateBand: '31–60 days', policyStatus: 'Active', lapseExpectedOn: null,
      agentCashHeld: null, agentCashWith: null, actions: [],
    }],
    total: 1, page: 0, pageSize: 5, dueToday: 0, dueSoon: 0, overdue: 1, overdueOwed: 240000,
  });
});

describe('AgentDashboardPanel', () => {
  it('leads with the customers to follow up, most overdue first, and links the whole list', async () => {
    renderScreen(<AgentDashboardPanel range={RANGE} />);

    expect(await screen.findByText('Neema Paid')).toBeInTheDocument();
    expect(screen.getByText('32 days late')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'See them all' })).toHaveAttribute('href', '/agents/follow-up');
    expect(listMyFollowUp).toHaveBeenCalledWith({ view: 'OVERDUE', soonDays: 7, page: 0, pageSize: 5 });
  });

  it("links each count to the agent's own filtered list", async () => {
    renderScreen(<AgentDashboardPanel range={RANGE} />);

    expect(await screen.findByRole('link', { name: '309 My clients' })).toHaveAttribute('href', '/agents/clients');
    expect(screen.getByRole('link', { name: '23 Clients awaiting KYC' })).toHaveAttribute(
      'href',
      '/agents/clients?kycStatus=PENDING',
    );
    expect(screen.getByRole('link', { name: '412 Policies in my book' })).toHaveAttribute('href', '/agents/policies');
    expect(getAgentDashboard).toHaveBeenCalledWith(RANGE);
  });

  /** The agent's job here is sending the papers: each pending client opens straight on its KYC tab. */
  it('sends each pending client to their KYC tab', async () => {
    renderScreen(<AgentDashboardPanel range={RANGE} />);

    expect(await screen.findByRole('link', { name: 'Send documents for Amina Hassan' })).toHaveAttribute(
      'href',
      '/agents/parties/party-1?tab=kyc',
    );
  });

  /** Commission in full, never abbreviated: an agent checks it against their statement to the shilling. */
  it('shows commission in full', async () => {
    renderScreen(<AgentDashboardPanel range={RANGE} />);

    expect(await screen.findByText('TZS 1,284,500.00')).toBeInTheDocument();
  });

  it('counts the applications still in underwriting and the book by product', async () => {
    renderScreen(<AgentDashboardPanel range={RANGE} />);

    expect(await screen.findByText('Applications in underwriting')).toBeInTheDocument();
    expect(screen.getByText('72.8%')).toBeInTheDocument();
    expect(screen.getByText('Term life')).toBeInTheDocument();
  });

  /** Plan 3: premium sold is a year of premium, in full; single premiums and savings deposits apart (D2, D3). */
  it('shows the sales in money, with single premiums and savings deposits apart', async () => {
    renderScreen(<AgentDashboardPanel range={RANGE} />);

    expect(await screen.findByText('TZS 1,200,000.00 a year')).toBeInTheDocument();
    expect(screen.getByText('100.0% up', { exact: false })).toBeInTheDocument();
    expect(screen.getByText('TZS 2,000,000.00')).toBeInTheDocument();
    expect(screen.getByText('75.0%')).toBeInTheDocument();
    expect(screen.getByText('TZS 500,000.00')).toBeInTheDocument();
    expect(screen.getByText('TZS 20,000.00')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /My sales report/ })).toHaveAttribute('href', '/agents/sales');
  });

  it('shows no Me / My team switch to an agent with no team', async () => {
    renderScreen(<AgentDashboardPanel range={RANGE} />);

    expect(await screen.findByRole('heading', { name: 'Your sales' })).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Whose sales' })).not.toBeInTheDocument();
  });

  /** A supervisor sees the team by default; "Me" asks the server for their own agent only. */
  it("lets a supervisor narrow the team's sales to their own", async () => {
    vi.mocked(getOwnTeam).mockResolvedValue([ME, { agentId: 'agent-2', partyId: 'party-2' }] as never);
    renderScreen(<AgentDashboardPanel range={RANGE} />);

    expect(await screen.findByRole('heading', { name: 'Your team’s sales' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Me' }));

    expect(await screen.findByRole('heading', { name: 'Your sales' })).toBeInTheDocument();
    await waitFor(() => expect(getAgentDashboard).toHaveBeenLastCalledWith({ ...RANGE, agentId: 'agent-me' }));
    expect(screen.getByRole('link', { name: /My sales report/ })).toHaveAttribute('href', '/agents/sales?show=me');
  });
});
