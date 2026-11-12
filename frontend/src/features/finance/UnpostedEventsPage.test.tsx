import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { listUnpostedEvents } from '@/api/finaccounting';
import type { UnpostedEventView } from '@/api/types';
import { idle } from '@/store/createResourceSlice';
import { useLedgerControlsStore } from '@/store/ledgerControlsStore';
import { grantAccess } from '@/test/access';
import { renderScreen } from '@/test/renderScreen';
import { UnpostedEventsPage } from './UnpostedEventsPage';

vi.mock('@/api/finaccounting');
vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { access_token: undefined } }) }));
vi.mock('@/auth/claims', async (orig) => ({
  ...(await orig<typeof import('@/auth/claims')>()),
  readIdentity: () => ({ subject: 'finance-one' }),
}));

const EVENT = {
  id: 'e-1', eventType: 'billing.PremiumCollected', sourceRef: 'inv-1', policyNumber: 'POL-1', period: '2026-11',
  currency: 'TZS', amounts: { premium: 700 }, attributes: {}, reason: 'REFUSED', detail: null, ruleVersion: null,
  attempts: 1, createdAt: '2026-11-12T08:00:00Z', lastAttemptAt: '2026-11-12T08:00:00Z', resolution: null,
  resolutionReason: null, resolvedBy: null, resolvedAt: null, journalEntryId: null,
  dismissalRequestedBy: null, dismissalRequestedAt: null, dismissalReason: null, dismissedAlone: false,
} as UnpostedEventView;

beforeEach(() => {
  useLedgerControlsStore.setState({ unposted: idle(), acting: {} } as never);
});

/** Findings 74 and 116 (2026-11-12): dismissing removes an amount for good, so it takes two people. */
describe('UnpostedEventsPage', () => {
  it('asks to dismiss, and says what dismissing means', async () => {
    grantAccess('ledger.view', 'ledger.manage');
    vi.mocked(listUnpostedEvents).mockResolvedValue([EVENT]);
    renderScreen(<UnpostedEventsPage />);

    expect(await screen.findByRole('button', { name: 'Ask to dismiss' })).toBeInTheDocument();
    expect(screen.getByText(/A dismissed amount is never booked/)).toBeInTheDocument();
  });

  it('lets a second approver confirm, and holds the one who asked', async () => {
    grantAccess('ledger.view', 'ledger.manage', 'ledger.approve');
    vi.mocked(listUnpostedEvents).mockResolvedValue([
      { ...EVENT, dismissalRequestedBy: 'finance-one', dismissalRequestedAt: '2026-11-12T09:00:00Z', dismissalReason: 'Billed in error' },
    ]);
    renderScreen(<UnpostedEventsPage />);

    expect(await screen.findByText(/Billed in error/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirm dismissal' })).toBeDisabled();
    expect(screen.getByText('You asked for this, so someone else must confirm it.')).toBeInTheDocument();
  });
});
