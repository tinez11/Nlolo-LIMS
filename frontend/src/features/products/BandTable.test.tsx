import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { BandTable, type BandColumn } from './BandTable';
import { continueFrom } from './bandRows';

const YEARS: BandColumn[] = [{ header: 'From year' }, { header: 'To year' }, { header: 'Charge %' }];

/** The table over a text value, as a form field holds it; the text is printed so a test can read what was written. */
function Harness({ initial, columns = YEARS, first }: { initial: string; columns?: BandColumn[]; first?: string }) {
  const [text, setText] = useState(initial);
  return (
    <>
      <BandTable label="Surrender charge bands" columns={columns} value={text} onChange={setText}
        next={continueFrom(0, 1, columns.length, first)} />
      <output data-testid="text">{JSON.stringify(text)}</output>
    </>
  );
}

const written = () => JSON.parse(screen.getByTestId('text').textContent ?? '""') as string;

describe('BandTable (the user, 2026-10-22: bands as a table, not lines of commas)', () => {
  it('shows each line as a row, and writes an edit back as the same text', async () => {
    render(<Harness initial={'1,1,10\n2,,5'} />);
    expect(screen.getByLabelText('Surrender charge bands row 1 charge %')).toHaveValue('10');
    expect(screen.getByLabelText('Surrender charge bands row 2 to year')).toHaveValue('');

    await userEvent.clear(screen.getByLabelText('Surrender charge bands row 2 charge %'));
    await userEvent.type(screen.getByLabelText('Surrender charge bands row 2 charge %'), '4');
    expect(written()).toBe('1,1,10\n2,,4');
  });

  it('adds a row that starts the year after the last one ends, and keeps a blank row while it is filled', async () => {
    render(<Harness initial={'1,3,10'} />);
    await userEvent.click(screen.getByRole('button', { name: /Add (first )?row/ }));
    expect(screen.getByLabelText('Surrender charge bands row 2 from year')).toHaveValue('4');

    render(<Harness initial="" first="1" />);
    await userEvent.click(screen.getAllByRole('button', { name: /Add (first )?row/ })[1]!);
    expect(screen.getAllByLabelText('Surrender charge bands row 1 from year')[1]).toHaveValue('1');
  });

  it('fills from a block pasted from Excel into any box, adding rows as needed', () => {
    render(<Harness initial={'1,1,10'} />);
    fireEvent.paste(screen.getByLabelText('Surrender charge bands row 1 from year'), {
      clipboardData: { getData: () => '1\t1\t10\n2\t5\t5\n6\t\t0' },
    });
    expect(written()).toBe('1,1,10\n2,5,5\n6,,0');
  });

  it('keeps a hidden column in its place, empty -- a unisex mortality band has no sex', () => {
    const columns: BandColumn[] = [
      { header: 'Age from' }, { header: 'Age to' }, { header: 'Sex', kind: 'sex', hidden: true }, { header: 'Rate per 1,000' },
    ];
    render(<Harness initial="" columns={columns} />);
    expect(screen.queryByText('Sex')).not.toBeInTheDocument();
    // An empty table offers Add row; then a three-column block lands in the three columns shown.
    fireEvent.click(screen.getByRole('button', { name: /Add (first )?row/ }));
    fireEvent.paste(screen.getByLabelText('Surrender charge bands row 1 age from'), {
      clipboardData: { getData: () => '18\t39\t1.2\n40\t\t4.5' },
    });
    expect(written()).toBe('18,39,,1.2\n40,,,4.5');
  });

  it('names its columns and offers a first row or a paste from Excel when empty', () => {
    render(<Harness initial="" />);
    expect(screen.getByRole('columnheader', { name: 'Charge %' })).toBeInTheDocument();
    expect(screen.getByText('No bands yet.')).toBeInTheDocument();
    fireEvent.paste(screen.getByLabelText('Paste surrender charge bands from Excel'), {
      clipboardData: { getData: () => '1\t5\t5\n6\t\t0' },
    });
    expect(written()).toBe('1,5,5\n6,,0');
  });

  it('edits the same value as text for whoever prefers lines', async () => {
    render(<Harness initial={'1,1,10'} />);
    await userEvent.click(screen.getByRole('button', { name: 'Edit surrender charge bands as text' }));
    expect(screen.getByRole('textbox', { name: 'Surrender charge bands' })).toHaveValue('1,1,10');
    await userEvent.type(screen.getByRole('textbox', { name: 'Surrender charge bands' }), '\n2,,0');
    await userEvent.click(screen.getByRole('button', { name: 'Edit surrender charge bands as a table' }));
    expect(screen.getByLabelText('Surrender charge bands row 2 charge %')).toHaveValue('0');
  });
});
