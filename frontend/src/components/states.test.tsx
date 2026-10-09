import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { LoadingBlock } from './states';

describe('LoadingBlock', () => {
  // The shape of what is coming, not a spinner in a 150px void: a panel's content arriving into
  // a block of lines moves the page far less than replacing a centred spinner (2026-10-09).
  it('draws placeholder lines rather than a spinner', () => {
    const { container } = render(<LoadingBlock label="Loading charges" />);
    expect(container.querySelectorAll('.animate-pulse').length).toBeGreaterThanOrEqual(3);
    expect(container.querySelector('.animate-spin')).toBeNull();
  });

  // The label stays VISIBLE, not sr-only. e2e waits for loading to end with
  // `getByText('Resolving plan')).not.toBeVisible()`, which an sr-only label would satisfy at
  // once -- before the load it was waiting for had finished.
  it('keeps its label on screen and announces it as status', () => {
    render(<LoadingBlock label="Resolving plan" />);
    const status = screen.getByRole('status');
    expect(status).toHaveTextContent('Resolving plan');
    expect(screen.getByText('Resolving plan')).not.toHaveClass('sr-only');
  });
});
