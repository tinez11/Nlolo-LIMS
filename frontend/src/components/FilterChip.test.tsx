import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { FilterChip } from './FilterChip';
import { StatusBadge } from './StatusBadge';

describe('FilterChip', () => {
  it('announces its own pressed state whether on or off', () => {
    render(
      <>
        <FilterChip label="All" active onClick={() => {}} />
        <FilterChip label="Open" active={false} onClick={() => {}} />
      </>,
    );
    expect(screen.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Open' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('fills a text chip with ink when it is the active one', () => {
    render(<FilterChip label="All" active onClick={() => {}} />);
    expect(screen.getByRole('button', { name: 'All' })).toHaveClass('bg-accent');
  });

  it('draws no ground behind a label that is already a pill', () => {
    // Seven screens filter by status and use the status badge itself as the label. A filled
    // chip behind a tinted badge is two pills for one control, and the hue is what is read.
    render(
      <FilterChip
        label={<StatusBadge kind="claim" value="REGISTERED" />}
        bare
        active
        onClick={() => {}}
      />,
    );
    const chip = screen.getByRole('button');
    expect(chip).toHaveClass('ring-2', 'ring-accent');
    expect(chip).not.toHaveClass('bg-accent');
    expect(chip).not.toHaveClass('bg-control');
  });
});
