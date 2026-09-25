import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Badge } from './badge';

describe('Badge', () => {
  it('is a neutral pill at the 12px floor', () => {
    render(<Badge>Group scheme</Badge>);
    const pill = screen.getByText('Group scheme');
    expect(pill).toHaveClass('rounded-full', 'bg-control', 'text-xs');
    // The Stamp Rule: colour reports state, and a tag reports kind. StatusBadge owns hue.
    expect(pill.className).not.toMatch(/status-/);
  });
});
