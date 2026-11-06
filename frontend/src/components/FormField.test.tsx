import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { FormField, RequiredNote } from './FormField';
import { Input, Select } from './ui/input';

/**
 * The `*` a required field carries, and what it must NOT do.
 *
 * It sits beside the label, never inside it: inside, it would become part of the field's
 * accessible name, so "Full name" would be announced "Full name star" and every test and e2e
 * spec that finds the field by its label would stop finding it. The meaning reaches assistive
 * tech as `aria-required` instead.
 */
describe('FormField required', () => {
  it('marks a required field with a * and aria-required, leaving its name untouched', () => {
    render(
      <FormField required label="Full name">
        <Input />
      </FormField>,
    );

    const input = screen.getByLabelText('Full name');
    expect(input).toHaveAttribute('aria-required', 'true');
    expect(screen.getByRole('textbox', { name: 'Full name' })).toBe(input);
    expect(screen.getByText('*')).toHaveAttribute('aria-hidden', 'true');
  });

  it('uses aria-required, not the native attribute, so the browser bubble never fires first', () => {
    render(
      <FormField required label="Sex">
        <Select>
          <option value="">Not recorded</option>
        </Select>
      </FormField>,
    );
    const select = screen.getByLabelText('Sex');
    expect(select).not.toHaveAttribute('required');
    expect(select).toHaveAttribute('aria-required', 'true');
  });

  it('draws nothing on an optional field', () => {
    render(
      <FormField label="Email">
        <Input />
      </FormField>,
    );
    expect(screen.getByLabelText('Email')).not.toHaveAttribute('aria-required');
    expect(screen.queryByText('*')).not.toBeInTheDocument();
  });

  it('keys the mark once per form, in the page language when given', () => {
    const { rerender } = render(<RequiredNote />);
    expect(screen.getByText('Required')).toBeInTheDocument();
    rerender(<RequiredNote label="Inahitajika" />);
    expect(screen.getByText('Inahitajika')).toBeInTheDocument();
  });
});
