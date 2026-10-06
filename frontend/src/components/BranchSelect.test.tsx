import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { FormField } from '@/components/FormField';
import { BranchSelect } from './BranchSelect';

vi.mock('@/store/refdataStore', () => ({
  useReferenceCodes: () => [
    { code: 'ARU', label: 'Arusha', value: 'ARU' },
    { code: 'DSM', label: 'Dar es Salaam', value: 'DSM' },
  ],
}));

describe('BranchSelect', () => {
  it('is named by the FormField it sits in', () => {
    render(
      <FormField label="Sale branch">
        <BranchSelect value="DSM" onChange={() => {}} />
      </FormField>,
    );
    expect(screen.getByRole('combobox', { name: 'Sale branch' })).toHaveValue('DSM');
  });

  it('shows its value while the list has not arrived', () => {
    render(<BranchSelect value="ZNZ" onChange={() => {}} />);
    expect(screen.getByRole('combobox')).toHaveValue('ZNZ');
  });
});
