import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { DatePicker, type DatePickerProps } from './DatePicker';

/**
 * The field is controlled, so a parent that ignores `onChange` would revert every
 * keystroke -- correct React semantics, and what `Controller` does on the real
 * forms. Tests that assert on typed text therefore need a real state holder;
 * tests that assert nothing is reported can pass a static `value`.
 */
function Controlled({
  initial = null,
  onValue,
  ...rest
}: Omit<DatePickerProps, 'value' | 'onChange'> & {
  initial?: string | null;
  onValue?: (iso: string | null) => void;
}) {
  const [value, setValue] = useState<string | null>(initial);
  return (
    <DatePicker
      {...rest}
      value={value}
      onChange={(iso) => {
        setValue(iso);
        onValue?.(iso);
      }}
    />
  );
}

/** The DatePicker's input is the first focusable node, so Tab enters the field on
 *  its first section -- clicking cannot place the caret in jsdom, which has no
 *  layout and puts it at the end of the value. */
const field = () => screen.getByRole('textbox');

/** react-day-picker names each day button with the full formatted date via its
 *  default `labelDayButton` (e.g. "Monday, August 10th, 2026"), not a bare number. */
const DAY_BUTTON_NAME = /\w+day, \w+ \d+.*\d{4}/;

describe('DatePicker', () => {
  it('shows the format itself when empty', () => {
    render(<DatePicker value={null} onChange={vi.fn()} />);
    expect(field()).toHaveValue('DD/MM/YYYY');
  });

  it('renders an existing ISO value in day/month/year order', () => {
    render(<DatePicker value="2026-08-01" onChange={vi.fn()} />);
    expect(field()).toHaveValue('01/08/2026');
  });

  it('typing all three sections reports one ISO date', async () => {
    const onValue = vi.fn();
    const user = userEvent.setup();
    render(<Controlled onValue={onValue} />);

    await user.tab();
    await user.keyboard('29031974');

    expect(field()).toHaveValue('29/03/1974');
    expect(onValue).toHaveBeenCalledWith('1974-03-29');
  });

  it('advances to the next section once a section is full', async () => {
    const user = userEvent.setup();
    render(<Controlled />);

    await user.tab();
    await user.keyboard('2903');

    // Day and month took two digits each and handed over unprompted; the year is
    // still untouched.
    expect(field()).toHaveValue('29/03/YYYY');
  });

  it('waits for a second digit rather than advancing on a merely impossible one', async () => {
    const user = userEvent.setup();
    render(<Controlled />);

    await user.tab();
    await user.keyboard('4');
    // No day starts with 4, but the section still waits -- advancing here is the
    // behaviour that was deliberately not adopted.
    expect(field()).toHaveValue('4D/MM/YYYY');

    await user.keyboard('5');
    // 45 is not a day, so the 5 starts the section over instead of being refused.
    expect(field()).toHaveValue('5D/MM/YYYY');
  });

  it('a section re-entered but not yet typed into replaces rather than appends', async () => {
    const user = userEvent.setup();
    render(<Controlled />);

    await user.tab();
    await user.keyboard('29');
    // Overshot: the day is 28, not 29. Going back must not append onto "29".
    await user.keyboard('{ArrowLeft}28');

    expect(field()).toHaveValue('28/MM/YYYY');
  });

  it('pads a single-digit section when leaving it', async () => {
    const user = userEvent.setup();
    render(<Controlled />);

    await user.tab();
    await user.keyboard('29/3/');

    expect(field()).toHaveValue('29/03/YYYY');
  });

  it('arrow keys step the active section', async () => {
    const user = userEvent.setup();
    render(<Controlled initial="2026-08-10" />);

    await user.tab();
    await user.keyboard('{ArrowUp}');
    expect(field()).toHaveValue('11/08/2026');

    await user.keyboard('{ArrowDown}{ArrowDown}');
    expect(field()).toHaveValue('09/08/2026');
  });

  it('treats a two-digit year literally and refuses it, rather than inferring a century', async () => {
    const onValue = vi.fn();
    const user = userEvent.setup();
    render(<Controlled onValue={onValue} />);

    await user.tab();
    await user.keyboard('290374');
    // Leaving the year section pads it to 0074 -- the year 74, not 1974.
    await user.tab();

    expect(field()).toHaveValue('29/03/0074');
    expect(screen.getByRole('alert')).toHaveTextContent('Date outside the allowed range');
    expect(onValue).not.toHaveBeenCalledWith('1974-03-29');
    expect(onValue).not.toHaveBeenCalledWith('0074-03-29');
  });

  it('shows an error and reports nothing for a date that does not exist', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<DatePicker value={null} onChange={onChange} />);

    await user.tab();
    await user.keyboard('31022026');

    expect(field()).toHaveValue('31/02/2026');
    expect(screen.getByRole('alert')).toHaveTextContent('Not a valid date');
    expect(field()).toHaveAttribute('aria-invalid', 'true');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('refuses a typed date outside the disabled range, as the calendar grid would', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(
      <DatePicker value={null} onChange={onChange} disabled={{ after: new Date(2026, 0, 1) }} />,
    );

    await user.tab();
    // Syntactically valid and a real date, but after the bound. The calendar
    // refuses this day, and the typed path must refuse it too rather than
    // accepting whatever bypasses the click UI.
    await user.keyboard('15062026');

    expect(screen.getByRole('alert')).toHaveTextContent('Date outside the allowed range');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('accepts a typed date inside the disabled range', async () => {
    const onValue = vi.fn();
    const user = userEvent.setup();
    render(<Controlled onValue={onValue} disabled={{ after: new Date(2026, 0, 1) }} />);

    await user.tab();
    await user.keyboard('15062025');

    expect(onValue).toHaveBeenCalledWith('2025-06-15');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('a previously valid date that is edited into an invalid one reports null', async () => {
    const onValue = vi.fn();
    const user = userEvent.setup();
    render(<Controlled initial="2026-08-10" onValue={onValue} />);

    await user.tab();
    await user.keyboard('{ArrowRight}{ArrowRight}{Backspace}');

    // The year is gone, so the field no longer holds a date -- leaving the old
    // one in form state would let the form submit a date the user replaced.
    expect(field()).toHaveValue('10/08/YYYY');
    expect(onValue).toHaveBeenCalledWith(null);
  });

  it('deleting every section reports null', async () => {
    const onValue = vi.fn();
    const user = userEvent.setup();
    render(<Controlled initial="2026-08-10" onValue={onValue} />);

    await user.tab();
    // Each section is cleared in turn: Backspace on an already-empty section
    // does not spill into the next one.
    await user.keyboard('{Backspace}{ArrowRight}{Backspace}{ArrowRight}{Backspace}');

    expect(field()).toHaveValue('DD/MM/YYYY');
    expect(onValue).toHaveBeenCalledWith(null);
  });

  it('select-all then delete clears the field, there being no clear button', async () => {
    const onValue = vi.fn();
    const user = userEvent.setup();
    render(<Controlled initial="2026-08-10" onValue={onValue} />);

    await user.tab();
    await user.keyboard('{Control>}a{/Control}{Backspace}');

    expect(field()).toHaveValue('DD/MM/YYYY');
    expect(onValue).toHaveBeenCalledWith(null);
    expect(screen.queryByRole('button', { name: 'Clear date' })).not.toBeInTheDocument();
  });

  it('the calendar button opens the picker and choosing a day reports an ISO date', async () => {
    const onValue = vi.fn();
    const user = userEvent.setup();
    render(<Controlled onValue={onValue} />);

    await user.click(screen.getByRole('button', { name: 'Choose date' }));
    await waitFor(() =>
      expect(screen.getAllByRole('button', { name: DAY_BUTTON_NAME }).length).toBeGreaterThan(0),
    );

    await user.click(screen.getAllByRole('button', { name: DAY_BUTTON_NAME })[10]!);

    expect(onValue).toHaveBeenCalledTimes(1);
    const [reported] = onValue.mock.calls[0] as [string];
    expect(reported).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    // The field shows what the calendar chose, in the field's own order.
    expect(field()).toHaveValue(
      `${reported.slice(8, 10)}/${reported.slice(5, 7)}/${reported.slice(0, 4)}`,
    );
  });

  it('disables a day before the given "before" bound', async () => {
    const user = userEvent.setup();
    render(<DatePicker value={null} onChange={vi.fn()} disabled={{ before: new Date(2099, 0, 15) }} />);

    await user.click(screen.getByRole('button', { name: 'Choose date' }));
    // Every day of the current month is before 2099, so all of them are refused.
    expect(screen.getAllByRole('button', { name: DAY_BUTTON_NAME })[5]!).toBeDisabled();
  });

  it('the year view offers only years the disabled range allows', async () => {
    const user = userEvent.setup();
    render(<DatePicker value={null} onChange={vi.fn()} disabled={{ after: new Date(2026, 0, 1) }} />);

    await user.click(screen.getByRole('button', { name: 'Choose date' }));
    // The header toggle is the only collapsed button in the popover -- the
    // Popover trigger itself reads expanded once open.
    await user.click(screen.getByRole('button', { expanded: false, name: /\d{4}/ }));

    expect(screen.getByRole('button', { name: '2026' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '2027' })).not.toBeInTheDocument();
    // The open end falls back to 120 years, so a date of birth is reachable.
    expect(screen.getByRole('button', { name: String(new Date().getFullYear() - 120) })).toBeInTheDocument();
  });

  it('choosing a year returns to the day grid on that year', async () => {
    const user = userEvent.setup();
    render(<DatePicker value="2026-08-10" onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: 'Choose date' }));
    await user.click(screen.getByRole('button', { name: /August 2026/ }));
    await user.click(screen.getByRole('button', { name: '1974' }));

    expect(screen.getByRole('button', { name: /August 1974/ })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: DAY_BUTTON_NAME }).length).toBeGreaterThan(0);
  });

  it('the calendar opens on a partially typed month and year', async () => {
    const user = userEvent.setup();
    render(<Controlled />);

    await user.tab();
    await user.keyboard('{ArrowRight}031974');
    await user.click(screen.getByRole('button', { name: 'Choose date' }));

    // Only the month and year were typed -- enough to navigate to, which is how a
    // date of birth actually gets entered.
    expect(screen.getByRole('button', { name: /March 1974/ })).toBeInTheDocument();
  });
});
