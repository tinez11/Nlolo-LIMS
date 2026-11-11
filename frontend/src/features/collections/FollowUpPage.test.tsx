import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import * as followUpApi from '@/api/followUp';
import type { FollowUpPage as Page, FollowUpRow } from '@/api/followUp';
import { idle } from '@/store/createResourceSlice';
import { useFollowUpStore } from '@/store/followUpStore';
import { clearAccess, grantAccess } from '@/test/access';
import { renderScreen } from '@/test/renderScreen';
import { FollowUpPage } from './FollowUpPage';

vi.mock('@/api/followUp', async (original) => ({
  ...(await original<typeof import('@/api/followUp')>()),
  listFollowUp: vi.fn(),
  listMyFollowUp: vi.fn(),
  exportFollowUp: vi.fn(),
  recordCall: vi.fn(),
  sendReminder: vi.fn(),
  collectionFigures: vi.fn(),
}));
vi.mock('@/api/products');
vi.mock('@/api/organisation');

const INVOICE = 'c0ffee00-0000-4000-8000-000000000001';

const row: FollowUpRow = {
  invoiceId: INVOICE,
  policyNumber: 'POL-7348198C',
  clientName: 'Asha Juma',
  clientPhone: '+255712000111',
  productName: 'Term life',
  agentId: null,
  agentName: 'Juma Senior (AG-1)',
  branchCode: 'DSM',
  owed: 240000,
  currency: 'TZS',
  dueDate: '2026-10-22',
  daysLate: 20,
  lateBand: '15–30 days',
  policyStatus: 'Active',
  lapseExpectedOn: '2026-11-20',
  agentCashHeld: null,
  agentCashWith: null,
  actions: [],
};

const page: Page = {
  rows: [row],
  total: 1,
  page: 0,
  pageSize: 50,
  dueToday: 0,
  dueSoon: 3,
  overdue: 1,
  overdueOwed: 240000,
};

beforeEach(() => {
  vi.clearAllMocks();
  useFollowUpStore.setState({ list: idle(), figures: idle(), acting: {}, query: null, mine: false });
  vi.mocked(followUpApi.listFollowUp).mockResolvedValue(page);
  vi.mocked(followUpApi.collectionFigures).mockResolvedValue({
    from: '2026-11-01', to: '2026-11-11', due: 1000000, paid: 830000, ratePercent: '83.0',
    dueTodayCount: 0, dueTodayOwed: 0, overdueCount: 1, overdueOwed: 240000, currency: 'TZS',
  });
  grantAccess('collections.view', 'collections.manage');
});

afterEach(() => clearAccess());

describe('FollowUpPage', () => {
  it('shows who to call, their phone as a link, and how late', async () => {
    renderScreen(<FollowUpPage />);

    expect(await screen.findByText('Asha Juma')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '+255712000111' })).toHaveAttribute('href', 'tel:+255712000111');
    expect(screen.getByText('20 days late')).toBeInTheDocument();
    expect(screen.getByText(/Will lapse on/)).toBeInTheDocument();
    expect(followUpApi.listFollowUp).toHaveBeenCalledWith(expect.objectContaining({ view: 'OVERDUE', soonDays: 7 }));
  });

  it('records a call with what happened and the note', async () => {
    vi.mocked(followUpApi.recordCall).mockResolvedValue({
      kind: 'CALL', outcome: 'WILL_PAY', promisedOn: null, note: 'Pays Friday', recordedBy: 'Asha',
      recordedAt: '2026-11-11T08:00:00Z',
    });
    const user = userEvent.setup();
    renderScreen(<FollowUpPage />);

    await user.click(await screen.findByRole('button', { name: /Record a call/ }));
    await user.selectOptions(screen.getByLabelText(/What happened/), 'WILL_PAY');
    await user.type(screen.getByLabelText(/Note/), 'Pays Friday');
    expect(screen.getByText(/A promise is a note only: reminders and letters still go\./)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save call' }));

    await waitFor(() =>
      expect(followUpApi.recordCall).toHaveBeenCalledWith({
        invoiceId: INVOICE, outcome: 'WILL_PAY', promisedOn: null, note: 'Pays Friday',
      }),
    );
  });

  it('sends a reminder once and holds the button while it goes', async () => {
    let finish: () => void = () => {};
    vi.mocked(followUpApi.sendReminder).mockImplementation(
      () => new Promise((resolve) => {
        finish = () => resolve({
          kind: 'REMINDER', outcome: null, promisedOn: null, note: null, recordedBy: 'Asha',
          recordedAt: '2026-11-11T08:00:00Z',
        });
      }),
    );
    const user = userEvent.setup();
    renderScreen(<FollowUpPage />);

    const button = await screen.findByRole('button', { name: /Send reminder/ });
    await user.click(button);
    expect(button).toHaveAttribute('aria-busy', 'true');
    await user.click(button);
    expect(followUpApi.sendReminder).toHaveBeenCalledTimes(1);
    finish();
    await waitFor(() => expect(followUpApi.listFollowUp).toHaveBeenCalledTimes(2));
  });

  it('shows no actions to someone who can only look', async () => {
    clearAccess();
    grantAccess('collections.view');
    renderScreen(<FollowUpPage />);

    expect(await screen.findByText('Asha Juma')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Send reminder/ })).not.toBeInTheDocument();
  });
});
