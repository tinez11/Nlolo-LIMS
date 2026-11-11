import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as billingApi from '@/api/billing';
import type { FieldReceiptView } from '@/api/types';
import { renderScreen } from '@/test/renderScreen';
import { HeldList } from './MyCashPage';

vi.mock('@/api/billing');
vi.mock('@/api/party');

const cash = (receiptId: string, policyNumber: string, amount: string): FieldReceiptView =>
  ({
    receiptId,
    policyNumber,
    status: 'PENDING_RECONCILIATION',
    amount: { amount, currencyCode: 'TZS' },
    capturedAtServer: '2026-11-11T08:00:00Z',
  }) as FieldReceiptView;

beforeEach(() => vi.clearAllMocks());

/**
 * "I've banked this" (2026-11-11). Ticking a row once crashed the page: the checkbox was read inside the state
 * updater, after React had let go of the event ("Cannot read properties of null (reading 'checked')").
 */
describe('the cash an agent still holds', () => {
  it('ticks and unticks rows, and sends only the ticked ones under the slip reference', async () => {
    vi.mocked(billingApi.markAgentCashBanked).mockResolvedValue([]);
    vi.mocked(billingApi.listAgentCash).mockResolvedValue([]);
    renderScreen(
      <HeldList agentId="agent-1" rows={[cash('r1', 'POL-1', '500.00'), cash('r2', 'POL-2', '1000.00')]} />,
    );

    expect(screen.getByText(/You hold/)).toHaveTextContent('TZS 1,500.00');
    const [first, second] = screen.getAllByRole('checkbox');
    fireEvent.click(first);
    fireEvent.click(second);
    expect(screen.getByRole('button', { name: "I've banked these 2" })).toBeEnabled();
    fireEvent.click(first);
    expect(screen.getByRole('button', { name: "I've banked this" })).toBeEnabled();

    fireEvent.change(screen.getByLabelText('Bank slip or mobile-money reference'), { target: { value: 'SLIP-77' } });
    fireEvent.click(screen.getByRole('button', { name: "I've banked this" }));
    await waitFor(() => expect(billingApi.markAgentCashBanked).toHaveBeenCalledWith('agent-1', ['r2'], 'SLIP-77'));
  });

  it('offers nothing to bank until a row is ticked', () => {
    renderScreen(<HeldList agentId="agent-1" rows={[cash('r1', 'POL-1', '500.00')]} />);
    expect(screen.getByRole('button', { name: "I've banked these" })).toBeDisabled();
  });
});
