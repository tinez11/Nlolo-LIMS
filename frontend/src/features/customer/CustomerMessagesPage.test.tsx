import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import * as portalApi from '@/api/portal';
import type { CustomerMessage } from '@/api/portal';
import { CustomerMessagesPage } from './CustomerMessagesPage';

vi.mock('@/api/portal');

const message: CustomerMessage = {
  messageId: 'm-1', title: 'Payment received', policyNumber: 'POL-1',
  body: 'We have received your payment of TZS 50,000.00 for policy POL-1. Thank you.', sentAt: '2026-10-08T08:00:00Z', read: false,
};

describe('CustomerMessagesPage', () => {
  it('opens a message to its text and marks it read', async () => {
    vi.mocked(portalApi.getCustomerMessages).mockResolvedValue([
      message,
      { ...message, messageId: 'm-0', title: 'Your cover has started', body: null, read: true },
    ]);
    vi.mocked(portalApi.markMessageRead).mockResolvedValue({ ...message, read: true });
    render(<CustomerMessagesPage />);

    const payment = await screen.findByRole('button', { name: /Payment received/ });
    expect(screen.queryByText(/We have received your payment/)).not.toBeInTheDocument();
    await userEvent.click(payment);
    expect(screen.getByText(/We have received your payment/)).toBeInTheDocument();
    expect(portalApi.markMessageRead).toHaveBeenCalledWith('m-1');

    // A message from before the text was kept says so rather than showing nothing.
    await userEvent.click(screen.getByRole('button', { name: /Your cover has started/ }));
    expect(screen.getByText(/was not kept/)).toBeInTheDocument();
    expect(portalApi.markMessageRead).toHaveBeenCalledTimes(1);
  });
});
