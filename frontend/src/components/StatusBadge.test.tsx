import { render, screen } from '@testing-library/react';
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

  it('flags an unrecognised literal instead of silently showing neutral', () => {
    render(<StatusBadge kind="policy" value="INVENTED_LATER" />);
    const el = screen.getByTitle('Unrecognised policy status: INVENTED_LATER');
    expect(el).toBeInTheDocument();
    expect(el.textContent).toContain('Invented later');
  });

  it('does not flag a known literal', () => {
    render(<StatusBadge kind="policy" value="ACTIVE" />);
    expect(screen.getByText('Active')).not.toHaveAttribute('title');
  });
});
