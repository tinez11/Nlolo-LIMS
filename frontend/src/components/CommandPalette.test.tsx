import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { FileText, ScrollText } from 'lucide-react';
import { useState } from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { CommandPalette } from './CommandPalette';

beforeAll(() => {
  // cmdk scrolls the active item into view; jsdom has no layout, so no scrollIntoView.
  Element.prototype.scrollIntoView = vi.fn();
});

const GROUPS = [
  {
    label: 'Policies & claims',
    items: [
      { group: 'policies-claims' as const, label: 'Policies', icon: FileText, to: 'policies' },
      { group: 'policies-claims' as const, label: 'Claims', icon: ScrollText, to: 'claims' },
    ],
  },
];

function Where() {
  return <output data-testid="where">{useLocation().pathname}</output>;
}

function renderPalette() {
  const onOpenChange = vi.fn();
  render(
    <MemoryRouter initialEntries={['/staff/policies']}>
      <Routes>
        <Route
          path="*"
          element={
            <>
              <CommandPalette realm="staff" groups={GROUPS} open onOpenChange={onOpenChange} />
              <Where />
            </>
          }
        />
      </Routes>
    </MemoryRouter>,
  );
  return { onOpenChange };
}

describe('CommandPalette', () => {
  it('lists the screens it was given, and goes to the one chosen', async () => {
    const { onOpenChange } = renderPalette();
    await userEvent.type(screen.getByRole('combobox'), 'clai');
    await userEvent.keyboard('{Enter}');
    expect(screen.getByTestId('where')).toHaveTextContent('/staff/claims');
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('offers to open an exact policy number', async () => {
    renderPalette();
    await userEvent.type(screen.getByRole('combobox'), 'pol-7k2q9');
    await userEvent.click(screen.getByRole('option', { name: /Open policy POL-7K2Q9/ }));
    expect(screen.getByTestId('where')).toHaveTextContent('/staff/policies/POL-7K2Q9');
  });

  it('says plainly that it does not search records', async () => {
    renderPalette();
    await userEvent.type(screen.getByRole('combobox'), 'Juma Rajabu');
    expect(screen.getByText(/does not search names or records/)).toBeInTheDocument();
  });
});

describe('CommandPalette, closing', () => {
  it('forgets the query when it closes, so reopening is a fresh box', async () => {
    // Escape and an overlay click both close through onOpenChange, not through a jump, so
    // clearing only inside go() left the next Ctrl+K showing stale text and a filtered list.
    function Harness() {
      const [open, setOpen] = useState(true);
      return (
        <MemoryRouter>
          <CommandPalette realm="staff" groups={GROUPS} open={open} onOpenChange={setOpen} />
          <button onClick={() => setOpen(true)}>reopen</button>
        </MemoryRouter>
      );
    }
    render(<Harness />);
    await userEvent.type(screen.getByRole('combobox'), 'claims');
    expect(screen.getByRole('combobox')).toHaveValue('claims');

    await userEvent.keyboard('{Escape}');
    await userEvent.click(screen.getByRole('button', { name: 'reopen' }));
    expect(screen.getByRole('combobox')).toHaveValue('');
  });
});
