import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { FamilyEditor } from './FamilyEditor';
import type { FamilyRow } from './groupFuneral';

const family = (dependants: FamilyRow['dependants'] = []): FamilyRow => ({
  reference: 'M001',
  mainName: 'Martin Lema',
  mainDateOfBirth: '1992-10-01',
  mainSex: '',
  beneficiaryName: '',
  beneficiaryRelationship: '',
  beneficiaryPhone: '',
  dependants,
});

/**
 * A family is offered only the roles the chosen plan covers (2026-10-19). It offered all four whatever the plan, so
 * plan NLevel2 offered extended family and only the proposal refused it -- the whole scheme, at the end.
 */
describe('FamilyEditor roles', () => {
  it('offers only the roles the plan covers', async () => {
    const onChange = vi.fn();
    render(<FamilyEditor family={family()} onChange={onChange} roles={['SPOUSE', 'CHILD']} />);

    await userEvent.click(screen.getByRole('button', { name: /Add family member/ }));

    // The new row arrives in the first covered role, not a hard-coded one.
    expect(onChange.mock.calls[0]![0].dependants[0].role).toBe('SPOUSE');
  });

  it('lists only covered roles in the role picker', () => {
    render(<FamilyEditor family={family([{ role: 'SPOUSE', fullName: 'Gorgina', dateOfBirth: '', sex: '', student: false }])}
      onChange={vi.fn()} roles={['SPOUSE', 'CHILD']} />);

    expect(screen.getByRole('option', { name: 'Spouse' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Extended family' })).not.toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Parent' })).not.toBeInTheDocument();
  });

  /** A row already holding an uncovered role keeps it visible, so the select never shows one role and holds another. */
  it('keeps a row its own role when the plan does not cover it', () => {
    render(<FamilyEditor family={family([{ role: 'EXTENDED', fullName: 'Martin Fredii', dateOfBirth: '', sex: '', student: false }])}
      onChange={vi.fn()} roles={['SPOUSE', 'CHILD']} />);

    expect(screen.getByLabelText('Role')).toHaveValue('EXTENDED');
  });

  it('offers no family to add on a plan for the main member only', () => {
    render(<FamilyEditor family={family()} onChange={vi.fn()} roles={[]} />);

    expect(screen.queryByRole('button', { name: /Add family member/ })).not.toBeInTheDocument();
    expect(screen.getByText('This plan covers the main member only.')).toBeInTheDocument();
  });

  it('still offers every role where the caller does not know the plan', () => {
    render(<FamilyEditor family={family([{ role: 'SPOUSE', fullName: '', dateOfBirth: '', sex: '', student: false }])}
      onChange={vi.fn()} />);

    expect(screen.getByRole('option', { name: 'Extended family' })).toBeInTheDocument();
  });
});
