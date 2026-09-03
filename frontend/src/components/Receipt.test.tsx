import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Receipt } from './Receipt';

describe('Receipt', () => {
  it('says what happened and carries the facts it was given', () => {
    render(
      <Receipt
        heading="Payout requested"
        lines={[
          { label: 'Period', value: '2026-08' },
          { label: 'Amount', value: 'TZS 1,240,000.00' },
        ]}
      />,
    );

    expect(screen.getByText('Payout requested')).toBeInTheDocument();
    expect(screen.getByText('Period')).toBeInTheDocument();
    expect(screen.getByText('TZS 1,240,000.00')).toBeInTheDocument();
  });

  /**
   * A result, not a problem. `alert` interrupts assertively and is for something
   * that has gone wrong; a completed action is announced once, politely.
   */
  it('is announced as a status rather than an alert', () => {
    render(<Receipt heading="Settlement approved" lines={[]} />);
    expect(screen.getByRole('status')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  /**
   * The whole point of it being persistent: a toast is gone in four seconds and
   * cannot be re-read, copied, or screenshotted for a file note. On a screen
   * where being wrong costs money, the record of what was done outlives the
   * moment.
   */
  it('stays rendered — nothing here dismisses itself', () => {
    render(<Receipt heading="Claim rejected" lines={[{ label: 'Status', value: 'REJECTED' }]} />);
    expect(screen.getByRole('status')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /dismiss|close/i })).not.toBeInTheDocument();
  });

  it('renders a bare heading when the endpoint returned nothing to show', () => {
    // `POST .../payout` answers 202 with no body. A receipt with no lines is the
    // honest rendering of that, not a bug to pad with invented fields.
    render(<Receipt heading="Payout requested" lines={[]} />);
    expect(screen.getByText('Payout requested')).toBeInTheDocument();
    expect(screen.queryByRole('definition')).not.toBeInTheDocument();
  });

  it('separates a client-side note from the server-returned lines', () => {
    render(
      <Receipt
        heading="Payout requested"
        lines={[{ label: 'Amount', value: 'TZS 500.00' }]}
        note="Requested, not yet paid: the disbursement settles asynchronously."
      />,
    );
    expect(screen.getByText(/not yet paid/)).toBeInTheDocument();
  });

  it('offers the way onward when one is given', () => {
    render(
      <Receipt
        heading="Settlement approved"
        lines={[]}
        onward={<a href="/staff/claims/abc">Back to the claim</a>}
      />,
    );
    expect(screen.getByRole('link', { name: 'Back to the claim' })).toBeInTheDocument();
  });
});
