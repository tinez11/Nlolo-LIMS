import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as benefitPayoutsApi from '@/api/benefitPayouts';
import type { PayoutInstalmentView } from '@/api/types';
import { useBenefitPayoutStore } from '@/store/benefitPayoutStore';
import { PayoutsPanel } from './PayoutsPanel';

vi.mock('@/api/benefitPayouts');

const POLICY = 'POL-000001';

const survival: PayoutInstalmentView = {
  instalmentId: '8f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f',
  policyNumber: POLICY,
  kind: 'SURVIVAL',
  dueDate: '2031-01-15',
  originalAmount: { amount: '100000.00', currencyCode: 'TZS' },
  currentAmount: { amount: '100000.00', currencyCode: 'TZS' },
  restatementReason: null,
  status: 'SCHEDULED',
  statusReason: null,
  streamId: null,
  payeeRef: null,
  proofOfLifeMethod: null,
  reviewedBy: null,
  approvedBy: null,
  paymentRunId: null,
  attempts: 0,
};

/**
 * Panel-level coverage for a policy's payout schedule.
 *
 * The EMPTY state in particular lives here rather than in e2e, and deliberately. Reaching it end
 * to end means issuing a whole policy — an underwriting case, a second underwriter's decision and
 * a manual issue — to assert one sentence of rendering with no server integration behind it. The
 * integrated flow that is worth e2e (a payout falling due, reviewed by one person and approved by
 * another) is proven in `staff-payouts.spec.ts`; this is the rendering.
 */
beforeEach(() => {
  vi.clearAllMocks();
  useBenefitPayoutStore.setState({ byPolicy: {} });
});

afterEach(() => {
  vi.restoreAllMocks();
});

function renderPanel() {
  return render(
    <MemoryRouter>
      <PayoutsPanel policyNumber={POLICY} />
    </MemoryRouter>,
  );
}

describe('PayoutsPanel', () => {
  it('says plainly that a policy pays nothing while the life assured is alive', async () => {
    vi.mocked(benefitPayoutsApi.listPolicyPayouts).mockResolvedValue([]);
    renderPanel();

    // A term policy carries no schedule at all. The tab has to say which, or it reads as broken.
    expect(await screen.findByText('No payouts scheduled')).toBeInTheDocument();
    expect(
      screen.getByText(/Term and whole-life cover pays on death/),
    ).toBeInTheDocument();
  });

  it('shows what the contract owes, and links to the payout under /staff', async () => {
    vi.mocked(benefitPayoutsApi.listPolicyPayouts).mockResolvedValue([survival]);
    renderPanel();

    expect(await screen.findByText('Survival benefit')).toBeInTheDocument();
    expect(screen.getByText('TZS 100,000.00')).toBeInTheDocument();
    // Every route on this console is mounted under /staff. An absolute link without it 404s,
    // which is exactly the defect the whole-branch review found here.
    expect(screen.getByRole('link', { name: 'Open payout' })).toHaveAttribute(
      'href',
      `/staff/payouts/${survival.instalmentId}`,
    );
  });

  it('shows BOTH figures once a paid-up conversion has shrunk one', async () => {
    vi.mocked(benefitPayoutsApi.listPolicyPayouts).mockResolvedValue([
      {
        ...survival,
        currentAmount: { amount: '40000.00', currencyCode: 'TZS' },
        restatementReason: 'Made paid-up: 400000.00 of 1000000.00 sum assured',
      },
    ]);
    renderPanel();

    // Showing the reduced figure alone would make a cut benefit look like the agreed one.
    expect(await screen.findByText('TZS 40,000.00')).toBeInTheDocument();
    expect(screen.getByText(/reduced from TZS 100,000.00/)).toBeInTheDocument();
    expect(
      screen.getByText('Made paid-up: 400000.00 of 1000000.00 sum assured'),
    ).toBeInTheDocument();
  });

  it("repeats the server's own words for a hold rather than paraphrasing them", async () => {
    vi.mocked(benefitPayoutsApi.listPolicyPayouts).mockResolvedValue([
      { ...survival, status: 'ON_HOLD', statusReason: 'Premiums are not paid up to the due date' },
    ]);
    renderPanel();

    await waitFor(() =>
      expect(
        screen.getByText('Premiums are not paid up to the due date'),
      ).toBeInTheDocument(),
    );
  });
});
