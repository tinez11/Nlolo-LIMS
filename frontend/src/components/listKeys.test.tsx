import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { DataTable, type Column } from './DataTable';
import { useListKeys } from './listKeys';

interface Row {
  id: string;
  name: string;
}
const rows: Row[] = [
  { id: '1', name: 'First' },
  { id: '2', name: 'Second' },
  { id: '3', name: 'Third' },
];
const columns: Column<Row>[] = [{ key: 'name', header: 'Name', render: (r) => r.name }];

function ListScreen() {
  useListKeys();
  return (
    <main id="main">
      <input aria-label="Search" data-list-filter />
      <input aria-label="Notes" />
      <DataTable columns={columns} rows={rows} rowKey={(r) => r.id} onRowActivate={() => {}} />
    </main>
  );
}

describe('useListKeys', () => {
  it('j and k walk the rows, and stop at either end', async () => {
    const user = userEvent.setup();
    render(<ListScreen />);
    await user.keyboard('j');
    expect(screen.getByRole('button', { name: 'First' })).toHaveFocus();
    await user.keyboard('jj');
    expect(screen.getByRole('button', { name: 'Third' })).toHaveFocus();
    await user.keyboard('j');
    expect(screen.getByRole('button', { name: 'Third' })).toHaveFocus();
    await user.keyboard('kkk');
    expect(screen.getByRole('button', { name: 'First' })).toHaveFocus();
  });

  it('/ goes to the list filter', async () => {
    const user = userEvent.setup();
    render(<ListScreen />);
    await user.keyboard('/');
    expect(screen.getByLabelText('Search')).toHaveFocus();
    expect(screen.getByLabelText('Search')).toHaveValue('');
  });

  // A shortcut that fires while someone types would eat their letters.
  it('stays out of the way while a field has focus', async () => {
    const user = userEvent.setup();
    render(<ListScreen />);
    await user.click(screen.getByLabelText('Notes'));
    await user.keyboard('jk/');
    expect(screen.getByLabelText('Notes')).toHaveFocus();
    expect(screen.getByLabelText('Notes')).toHaveValue('jk/');
  });
});
