import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ConfirmAct } from './ConfirmAct';

function renderConfirm(overrides: Partial<Parameters<typeof ConfirmAct>[0]> = {}) {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();
  render(
    <ConfirmAct
      heading="Pay out this statement?"
      consequence="Send TZS 1,240,000.00 to Juma Senior."
      reversal="Money leaves through the payment rail and cannot be recalled."
      confirmLabel="Pay out"
      onConfirm={onConfirm}
      onCancel={onCancel}
      {...overrides}
    />,
  );
  return { onConfirm, onCancel };
}

describe('ConfirmAct', () => {
  it('states the heading, the consequence and what happens if it is wrong', () => {
    renderConfirm();
    expect(screen.getByText('Pay out this statement?')).toBeInTheDocument();
    expect(screen.getByText('Send TZS 1,240,000.00 to Juma Senior.')).toBeInTheDocument();
    expect(screen.getByText(/cannot be recalled/)).toBeInTheDocument();
  });

  it('commits only when the confirming button is pressed', async () => {
    const user = userEvent.setup();
    const { onConfirm, onCancel } = renderConfirm();

    await user.click(screen.getByRole('button', { name: 'Pay out' }));
    expect(onConfirm).toHaveBeenCalledOnce();
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('backs out without committing', async () => {
    const user = userEvent.setup();
    const { onConfirm, onCancel } = renderConfirm();

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalledOnce();
    expect(onConfirm).not.toHaveBeenCalled();
  });

  /**
   * The confirming button carries the verb, never "Confirm" or "Yes". Two
   * identical-looking buttons a click apart is how the second click becomes as
   * automatic as the first — which would leave the whole step as theatre.
   */
  it('labels the confirming button with the action, not a generic assent', () => {
    renderConfirm({ confirmLabel: 'Approve and pay' });
    expect(screen.getByRole('button', { name: 'Approve and pay' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^(Confirm|Yes|OK)$/ })).not.toBeInTheDocument();
  });

  it('cannot be double-committed while the mutation is in flight', async () => {
    const user = userEvent.setup();
    const { onConfirm } = renderConfirm({ busy: true });

    const confirm = screen.getByRole('button', { name: 'Working…' });
    expect(confirm).toBeDisabled();
    await user.click(confirm);
    expect(onConfirm).not.toHaveBeenCalled();
    // Cancel is disabled too: backing out mid-flight would hide a request that
    // is still going to land.
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
  });

  /**
   * `group`, not `alertdialog`. Nothing here traps focus or blocks the page, and
   * announcing a dialog the user is not actually inside describes an interaction
   * that does not exist.
   */
  it('is announced as a named group rather than a dialog it is not', () => {
    renderConfirm();
    expect(screen.getByRole('group', { name: 'Pay out this statement?' })).toBeInTheDocument();
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('carries the danger tone for money leaving', () => {
    const { container } = render(
      <ConfirmAct
        heading="Waive this invoice?"
        consequence="Write off TZS 50,000.00."
        reversal="Waived is permanent."
        confirmLabel="Waive invoice"
        tone="danger"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(container.firstElementChild?.className).toContain('status-danger');
  });
});
