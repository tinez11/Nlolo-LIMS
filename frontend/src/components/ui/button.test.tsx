import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Button } from './button';

describe('Button', () => {
  it('defaults to the filled secondary style', () => {
    render(<Button>Save</Button>);
    expect(screen.getByRole('button', { name: 'Save' })).toHaveClass('bg-control');
  });

  it('is disabled and busy while pending, and keeps its name', () => {
    render(<Button pending>Save</Button>);
    const button = screen.getByRole('button', { name: 'Save' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
  });

  it('stays disabled when disabled even if not pending', () => {
    render(<Button disabled>Save</Button>);
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Save' })).not.toHaveAttribute('aria-busy');
  });

  it('reaches 44px on a coarse pointer at every size', () => {
    render(
      <>
        <Button size="sm">A</Button>
        <Button size="md">B</Button>
        <Button size="icon" aria-label="C" />
      </>,
    );
    expect(screen.getByRole('button', { name: 'A' })).toHaveClass('pointer-coarse:h-11');
    expect(screen.getByRole('button', { name: 'B' })).toHaveClass('pointer-coarse:h-11');
    expect(screen.getByRole('button', { name: 'C' })).toHaveClass('pointer-coarse:size-11');
  });

  it('still renders a link when asChild is used', () => {
    render(
      <Button asChild>
        <a href="/staff/policies">Policies</a>
      </Button>,
    );
    expect(screen.getByRole('link', { name: 'Policies' })).toHaveClass('bg-control');
  });
});
