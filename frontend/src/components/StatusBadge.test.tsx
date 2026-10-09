import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { StatusBadge } from './StatusBadge';

describe('StatusBadge', () => {
  it('renders the humanized literal', () => {
    render(<StatusBadge kind="claim" value="SETTLEMENT_REQUESTED" />);
    expect(screen.getByText('Settlement requested')).toBeInTheDocument();
  });

  it('applies the bucket colour for the domain', () => {
    const { container } = render(<StatusBadge kind="policy" value="LAPSED" />);
    expect(container.firstElementChild?.className).toContain('status-danger');
  });

  it('colours the same literal differently per domain', () => {
    const uw = render(<StatusBadge kind="underwritingCase" value="OPEN" />);
    expect(uw.container.firstElementChild?.className).toContain('status-pending');

    const stmt = render(<StatusBadge kind="commissionStatement" value="OPEN" />);
    expect(stmt.container.firstElementChild?.className).toContain('status-active');
  });

  // Absent is not a status: a grey "Unknown" pill would imply the backend said
  // something when it said nothing at all.
  it.each([null, undefined, ''])('renders a dash for %s rather than a pill', (value) => {
    const { container } = render(<StatusBadge kind="policy" value={value} />);
    expect(container.textContent).toBe('—');
    expect(container.firstElementChild?.className).not.toContain('status-');
  });

  // The flag explains itself to a keyboard as well as a mouse: it was a title attribute, which
  // nobody tabbing through a list could ever read.
  it('flags an unrecognised literal instead of silently showing neutral', async () => {
    const user = userEvent.setup();
    render(<StatusBadge kind="policy" value="INVENTED_LATER" />);
    expect(screen.getByText('Invented later')).toBeInTheDocument();
    await user.tab();
    expect(await screen.findByRole('tooltip')).toHaveTextContent(
      'Unrecognised policy status: INVENTED_LATER',
    );
  });

  it('does not flag a known literal, and adds no tab stop for it', async () => {
    const user = userEvent.setup();
    render(<StatusBadge kind="policy" value="ACTIVE" />);
    await user.tab();
    expect(document.body).toHaveFocus();
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });
});
