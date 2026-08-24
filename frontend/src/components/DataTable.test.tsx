import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { DataTable, Pager, type Column } from './DataTable';

interface Row {
  id: string;
  name: string;
  amount: string;
}

const rows: Row[] = [
  { id: '1', name: 'First', amount: '10.00' },
  { id: '2', name: 'Second', amount: '20.00' },
];

const columns: Column<Row>[] = [
  { key: 'name', header: 'Name', render: (r) => r.name },
  { key: 'amount', header: 'Amount', render: (r) => r.amount, align: 'right' },
];

describe('DataTable', () => {
  it('renders headers and rows', () => {
    render(<DataTable columns={columns} rows={rows} rowKey={(r) => r.id} />);
    expect(screen.getByRole('columnheader', { name: 'Name' })).toBeInTheDocument();
    expect(screen.getByText('First')).toBeInTheDocument();
    expect(screen.getByText('20.00')).toBeInTheDocument();
  });

  // A click handler on <tr> is invisible to keyboards and screen readers. The row's
  // activation must be a real control.
  it('exposes row activation as a button, reachable by keyboard', async () => {
    const onRowActivate = vi.fn();
    render(
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(r) => r.id}
        onRowActivate={onRowActivate}
      />,
    );

    const first = screen.getByRole('button', { name: 'First' });
    await userEvent.click(first);
    expect(onRowActivate).toHaveBeenCalledWith(rows[0]);

    await userEvent.tab();
    await userEvent.keyboard('{Enter}');
    expect(onRowActivate).toHaveBeenCalledTimes(2);
  });

  it('renders no buttons when rows are not activatable', () => {
    render(<DataTable columns={columns} rows={rows} rowKey={(r) => r.id} />);
    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });

  it('marks the selected row for assistive tech, not just visually', () => {
    render(
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(r) => r.id}
        isRowSelected={(r) => r.id === '2'}
      />,
    );
    const selected = screen.getAllByRole('row').filter((r) => r.getAttribute('aria-current'));
    expect(selected).toHaveLength(1);
    expect(selected[0]).toHaveTextContent('Second');
  });

  it('renders an empty tbody without crashing', () => {
    render(<DataTable columns={columns} rows={[]} rowKey={(r) => r.id} />);
    expect(screen.getByRole('columnheader', { name: 'Name' })).toBeInTheDocument();
    expect(screen.queryByText('First')).not.toBeInTheDocument();
  });
});

describe('Pager', () => {
  it('describes the current window in human terms', () => {
    render(
      <Pager page={{ page: 0, pageSize: 20, totalElements: 57 }} onPageChange={() => {}} />,
    );
    expect(screen.getByText('1–20 of 57')).toBeInTheDocument();
  });

  it('computes the final partial page correctly', () => {
    render(
      <Pager page={{ page: 2, pageSize: 20, totalElements: 57 }} onPageChange={() => {}} />,
    );
    expect(screen.getByText('41–57 of 57')).toBeInTheDocument();
  });

  it('disables previous on the first page and next on the last', () => {
    const first = render(
      <Pager page={{ page: 0, pageSize: 20, totalElements: 57 }} onPageChange={() => {}} />,
    );
    expect(first.getByRole('button', { name: 'Previous page' })).toBeDisabled();
    expect(first.getByRole('button', { name: 'Next page' })).toBeEnabled();
    first.unmount();

    const last = render(
      <Pager page={{ page: 2, pageSize: 20, totalElements: 57 }} onPageChange={() => {}} />,
    );
    expect(last.getByRole('button', { name: 'Previous page' })).toBeEnabled();
    expect(last.getByRole('button', { name: 'Next page' })).toBeDisabled();
  });

  it('says so plainly when there is nothing, rather than showing 1-0 of 0', () => {
    render(<Pager page={{ page: 0, pageSize: 20, totalElements: 0 }} onPageChange={() => {}} />);
    expect(screen.getByText('No results')).toBeInTheDocument();
  });

  it('handles a single full page -- exactly pageSize elements', () => {
    render(<Pager page={{ page: 0, pageSize: 20, totalElements: 20 }} onPageChange={() => {}} />);
    expect(screen.getByText('1–20 of 20')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();
  });

  it('moves pages', async () => {
    const onPageChange = vi.fn();
    render(
      <Pager page={{ page: 1, pageSize: 20, totalElements: 57 }} onPageChange={onPageChange} />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(onPageChange).toHaveBeenCalledWith(2);
    await userEvent.click(screen.getByRole('button', { name: 'Previous page' }));
    expect(onPageChange).toHaveBeenCalledWith(0);
  });

  it('locks both controls while a page is in flight', () => {
    render(
      <Pager page={{ page: 1, pageSize: 20, totalElements: 57 }} onPageChange={() => {}} busy />,
    );
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();
  });
});
