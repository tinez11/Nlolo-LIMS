import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { GroupFuneralFamilyView } from '@/api/types';
import { SchemeFamilyChooser } from './SchemeFamilyChooser';

const family = (reference: string, main: string): GroupFuneralFamilyView => ({
  policyMemberId: `m-${reference}`, memberReference: reference, mainMemberName: main, status: 'ACTIVE',
  joinedOn: '2026-01-01', leftOn: null, beneficiaryName: null, beneficiaryRelationship: null, beneficiaryPhone: null,
  familyCover: 0, lives: [],
});

const families = [family('M001', 'martin lema'), family('M002', 'Rehema Said'), family('M003', 'Juma Ali')];

/** The claim form around the chooser: it holds the chosen family, and a submit would file the claim. */
function ClaimForm({ onSubmit }: { onSubmit: () => void }) {
  const [chosen, setChosen] = useState<string | null>(null);
  return (
    <form onSubmit={(e) => { e.preventDefault(); onSubmit(); }}>
      <SchemeFamilyChooser families={families} family={families.find((f) => f.policyMemberId === chosen) ?? null}
        onChoose={setChosen} />
    </form>
  );
}

describe('SchemeFamilyChooser', () => {
  it('chooses the one family a search matches, says so, and Enter does not submit the claim', async () => {
    const submit = vi.fn();
    render(<ClaimForm onSubmit={submit} />);

    await userEvent.type(screen.getByLabelText('Search families'), 'lema{Enter}');

    expect(screen.getByRole('status')).toHaveTextContent('1 family matches — M001 · martin lema, chosen below.');
    expect(screen.getByLabelText('Family')).toHaveValue('m-M001');
    expect(submit).not.toHaveBeenCalled();
  });

  it('says how many families match when there are several, and when there are none', async () => {
    render(<ClaimForm onSubmit={vi.fn()} />);
    const search = screen.getByLabelText('Search families');

    await userEvent.type(search, 'M00');
    expect(screen.getByRole('status')).toHaveTextContent('3 families match — choose one below.');
    expect(screen.getByLabelText('Family')).toHaveValue('');

    await userEvent.clear(search);
    await userEvent.type(search, 'zzz');
    expect(screen.getByRole('status')).toHaveTextContent('No family matches “zzz”.');
  });
});
