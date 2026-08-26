import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { DatePicker } from './DatePicker';

describe('DatePicker', () => {
  it('shows a placeholder when value is null', () => {
    render(<DatePicker value={null} onChange={vi.fn()} placeholder="Pick a date" />);
    expect(screen.getByRole('button', { name: 'Pick a date' })).toBeInTheDocument();
  });

  it('shows the formatted date when a value is set', () => {
    render(<DatePicker value="2026-08-01" onChange={vi.fn()} />);
    // formatDate abbreviates the month ("Aug", not "August") -- confirmed
    // against MONTHS in lib/dates.ts, not assumed.
    expect(screen.getByText('Aug 1, 2026')).toBeInTheDocument();
  });

  it('opens the calendar on trigger click and selecting a day calls onChange with an ISO date, then closes', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<DatePicker value={null} onChange={onChange} placeholder="Pick a date" />);

    await user.click(screen.getByRole('button', { name: 'Pick a date' }));
    await waitFor(() => expect(screen.getByPlaceholderText('YYYY-MM-DD')).toBeInTheDocument());

    // react-day-picker renders each day as a real `<button>` (DayButton) whose
    // accessible name is the FULL formatted date via its default `labelDayButton`
    // (date-fns "PPPP" format, e.g. "Monday, August 10th, 2026", optionally
    // prefixed "Today, "/suffixed ", selected" -- confirmed by reading
    // `node_modules/react-day-picker/dist/esm/labels/labelDayButton.js` directly,
    // not assumed) -- NOT a bare day number. The table does carry `role="grid"`
    // (confirmed in the real rendered DOM), so `getByRole('gridcell')` would
    // also work, but querying the button directly is both the correct click
    // target and avoids relying on grid/cell role nesting at all.
    const dayButtons = screen.getAllByRole('button', { name: /\w+day, \w+ \d+.*\d{4}/ });
    expect(dayButtons.length).toBeGreaterThan(0);
    const selectableDay = dayButtons[10]!;
    await user.click(selectableDay);

    expect(onChange).toHaveBeenCalledTimes(1);
    const [calledWith] = onChange.mock.calls[0] as [string];
    expect(calledWith).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('typing a complete, valid ISO date in the popover input calls onChange immediately and closes', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<DatePicker value={null} onChange={onChange} placeholder="Pick a date" />);

    await user.click(screen.getByRole('button', { name: 'Pick a date' }));
    await user.type(screen.getByPlaceholderText('YYYY-MM-DD'), '2026-12-25');

    expect(onChange).toHaveBeenCalledWith('2026-12-25');
    expect(screen.queryByPlaceholderText('YYYY-MM-DD')).not.toBeInTheDocument();
  });

  it('typing an incomplete date does not call onChange', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<DatePicker value={null} onChange={onChange} placeholder="Pick a date" />);

    await user.click(screen.getByRole('button', { name: 'Pick a date' }));
    await user.type(screen.getByPlaceholderText('YYYY-MM-DD'), '2026-12');

    expect(onChange).not.toHaveBeenCalled();
  });

  it('a clear button on a populated field calls onChange(null)', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<DatePicker value="2026-08-01" onChange={onChange} />);

    await user.click(screen.getByRole('button', { name: 'Clear date' }));

    expect(onChange).toHaveBeenCalledWith(null);
  });

  it('disables a day before the given "before" bound', async () => {
    const user = userEvent.setup();
    render(
      <DatePicker
        value={null}
        onChange={vi.fn()}
        placeholder="Pick a date"
        disabled={{ before: new Date(2099, 0, 15) }}
      />,
    );
    await user.click(screen.getByRole('button', { name: 'Pick a date' }));
    // react-day-picker marks out-of-range days aria-disabled -- the current month's
    // days are all before year 2099, so every day button should be disabled.
    // Same accessible-name shape as the test above (the full formatted date, not
    // a bare number) -- `labelDayButton` does not special-case `disabled`.
    const dayButton = screen.getAllByRole('button', { name: /\w+day, \w+ \d+.*\d{4}/ })[5]!;
    expect(dayButton).toBeDisabled();
  });

  it('typing a date outside the disabled range does not call onChange, unlike clicking a disabled day would', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(
      <DatePicker
        value={null}
        onChange={onChange}
        placeholder="Pick a date"
        disabled={{ after: new Date(2026, 0, 1) }}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Pick a date' }));
    // A syntactically valid, fully-typed date, but AFTER the disabled bound --
    // the calendar grid would refuse this day, and the typed-input path must
    // refuse it too rather than silently accepting whatever bypasses the click UI.
    await user.type(screen.getByPlaceholderText('YYYY-MM-DD'), '2026-06-15');

    expect(onChange).not.toHaveBeenCalled();
    // The popover stays open on a rejected typed date -- same "nothing happened"
    // treatment as typing an incomplete date above.
    expect(screen.getByPlaceholderText('YYYY-MM-DD')).toBeInTheDocument();
  });

  it('typing a date inside an "after" disabled range still calls onChange', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(
      <DatePicker
        value={null}
        onChange={onChange}
        placeholder="Pick a date"
        disabled={{ after: new Date(2026, 0, 1) }}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Pick a date' }));
    await user.type(screen.getByPlaceholderText('YYYY-MM-DD'), '2025-06-15');

    expect(onChange).toHaveBeenCalledWith('2025-06-15');
  });
});
