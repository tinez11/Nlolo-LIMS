import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRef } from 'react';
import { describe, expect, it } from 'vitest';
import { CheckboxField } from './checkbox';

describe('CheckboxField', () => {
  it('is named by its label and toggles from a click on the words', async () => {
    render(<CheckboxField label="Flag for fraud review" />);
    const box = screen.getByRole('checkbox', { name: 'Flag for fraud review' });
    await userEvent.click(screen.getByText('Flag for fraud review'));
    expect(box).toBeChecked();
  });

  it('forwards its ref, so react-hook-form register() still works', () => {
    const ref = createRef<HTMLInputElement>();
    render(<CheckboxField label="Permanent" ref={ref} />);
    expect(ref.current).toBeInstanceOf(HTMLInputElement);
  });

  it('gives a finger 44px to hit', () => {
    render(<CheckboxField label="Revocable" />);
    expect(screen.getByText('Revocable').closest('label')).toHaveClass('pointer-coarse:min-h-11');
  });
});
