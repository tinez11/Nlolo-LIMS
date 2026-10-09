import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as claimsApi from '@/api/claims';
import * as documentsApi from '@/api/documents';
import * as portalApi from '@/api/portal';
import type { CustomerPolicySummary } from '@/api/portal';
import type { ClaimView } from '@/api/types';
import * as download from '@/lib/download';
import { CustomerDocumentsPage } from './CustomerDocumentsPage';

vi.mock('@/api/portal');
vi.mock('@/api/claims');
vi.mock('@/api/documents', async (original) => ({
  ...(await original<typeof import('@/api/documents')>()),
  downloadPolicySchedule: vi.fn(), listReceipts: vi.fn(), downloadReceipt: vi.fn(),
  downloadPaymentSchedule: vi.fn(), downloadSavingsStatement: vi.fn(),
}));
vi.mock('@/lib/download', () => ({ saveBlob: vi.fn() }));

const policy = (over: Partial<CustomerPolicySummary> = {}): CustomerPolicySummary => ({
  policyNumber: 'POL-ABFE4139', productName: 'Nuru Funeral Cover', productCategory: 'FUNERAL', status: 'ACTIVE',
  sumAssured: 0, currency: 'TZS', premium: 100000, premiumFrequency: 'ANNUALLY', nextDueDate: null, nextDueAmount: null,
  value: null, ...over,
});

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(claimsApi.searchClaims).mockResolvedValue({ items: [], page: { page: 0, pageSize: 50, totalElements: 0 } });
});

function dashboardWith(...policies: CustomerPolicySummary[]) {
  vi.mocked(portalApi.getCustomerDashboard).mockResolvedValue({
    displayName: 'Nadine Kileo', activePolicies: policies.length, claimsInProgress: 0, unreadMessages: 0, nextPremium: null, accountValue: null, policies,
  });
}

describe('CustomerDocumentsPage', () => {
  it('offers each policy its schedule and premium schedule, and a savings statement only where there is an account', async () => {
    dashboardWith(policy(), policy({ policyNumber: 'POL-SAVE1', productName: 'Elimu Savings', productCategory: 'EDUCATION_SAVINGS', value: 250000 }));
    render(<CustomerDocumentsPage />);

    expect(await screen.findByText('Nuru Funeral Cover')).toBeInTheDocument();
    expect(screen.getAllByText('Policy schedule')).toHaveLength(2);
    expect(screen.getAllByText('Premium schedule')).toHaveLength(2);
    expect(screen.getAllByText(/^Savings statement/)).toHaveLength(1);

    vi.mocked(documentsApi.downloadPolicySchedule).mockResolvedValue(new Blob(['pdf']));
    await userEvent.click(screen.getByRole('button', { name: 'Download the policy schedule for POL-ABFE4139' }));
    await waitFor(() => expect(download.saveBlob).toHaveBeenCalledWith(expect.any(Blob), 'policy-schedule-POL-ABFE4139.pdf'));
  });

  it('lists the premiums received, each with its receipt', async () => {
    dashboardWith(policy());
    vi.mocked(documentsApi.listReceipts).mockResolvedValue([{ receiptId: 'r-1', receivedOn: '2026-10-08', amount: 100000,
      currency: 'TZS', reference: 'MM-123', paidBy: '+255715000001', coversFrom: '2026-10-08', coversTo: '2027-10-07' }]);
    vi.mocked(documentsApi.downloadReceipt).mockResolvedValue(new Blob(['pdf']));
    render(<CustomerDocumentsPage />);

    await userEvent.click(await screen.findByRole('button', { name: /Premium receipts/ }));
    expect(await screen.findByText('TZS 100,000.00')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Download the receipt of/ }));
    await waitFor(() => expect(documentsApi.downloadReceipt).toHaveBeenCalledWith('POL-ABFE4139', 'r-1'));
  });

  it('shows the documents sent with a claim', async () => {
    dashboardWith(policy());
    vi.mocked(claimsApi.searchClaims).mockResolvedValue({
      items: [{ claimId: 'c-1', policyNumber: 'POL-ABFE4139', claimType: 'DEATH', status: 'SETTLED' } as ClaimView],
      page: { page: 0, pageSize: 50, totalElements: 1 },
    });
    vi.mocked(claimsApi.listClaimEvidence).mockResolvedValue([{ claimEvidenceId: 'e-1', claimId: 'c-1', documentRef: 'doc-1',
      description: 'Death certificate', uploadedBy: 'u', uploadedByName: 'Daudi', uploadedAt: '2026-10-08T10:00:00Z' }]);
    render(<CustomerDocumentsPage />);

    await userEvent.click(await screen.findByRole('button', { name: /death claim/ }));
    expect(await screen.findByText('Death certificate')).toBeInTheDocument();
  });
});
