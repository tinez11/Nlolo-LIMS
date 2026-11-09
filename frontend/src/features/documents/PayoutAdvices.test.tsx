import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as documents from '@/api/documents';
import * as download from '@/lib/download';
import { PayoutAdvices } from './PayoutAdvices';

vi.mock('@/api/documents');
vi.mock('@/lib/download');

beforeEach(() => vi.clearAllMocks());

/** 2026-11-09, the user: "i dont know where the receipts after transaction be retrieved". */
describe('the payments made on a policy', () => {
  it('lists each payment with what it was, when, how and to whom, and downloads its advice', async () => {
    vi.mocked(documents.listPayoutAdvices).mockResolvedValue([{
      disbursementId: 'd-1', adviceNumber: 'PA-2026-3F2A91BC', paidOn: '2026-11-04', amount: 200000, currency: 'TZS',
      what: 'Claim settlement', paidTo: '+255700000001', method: 'Mobile money', reference: 'MM-77',
    }]);
    vi.mocked(documents.downloadPayoutAdvice).mockResolvedValue(new Blob(['pdf']));
    render(<PayoutAdvices policyNumber="POL-1" />);

    expect(await screen.findByText('Claim settlement · TZS 200,000.00')).toBeInTheDocument();
    expect(screen.getByText(/by mobile money to \+255700000001/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Download payout advice PA-2026-3F2A91BC' }));
    expect(documents.downloadPayoutAdvice).toHaveBeenCalledWith('POL-1', 'd-1');
    expect(download.saveBlob).toHaveBeenCalledWith(expect.any(Blob), 'PA-2026-3F2A91BC.pdf');
  });

  it('says so when nothing has been paid', async () => {
    vi.mocked(documents.listPayoutAdvices).mockResolvedValue([]);
    render(<PayoutAdvices policyNumber="POL-1" />);
    expect(await screen.findByText('Nothing has been paid out on this policy yet.')).toBeInTheDocument();
  });
});
