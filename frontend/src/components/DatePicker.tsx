import * as Popover from '@radix-ui/react-popover';
import { X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { DayPicker, dateMatchModifiers, type Matcher } from 'react-day-picker';
import 'react-day-picker/style.css';
import { formatDate } from '@/lib/dates';
import { ISO_DATE_PATTERN } from '@/lib/patterns';

export interface DatePickerProps {
  /** ISO yyyy-MM-dd, or null. Same shape a native `<input type="date">` already
   *  produces, so form schemas built around `ISO_DATE_PATTERN` need no change. */
  value: string | null;
  onChange: (isoDate: string | null) => void;
  placeholder?: string;
  /** Disallows a range of dates, e.g. no date of event in the future. */
  disabled?: { before?: Date; after?: Date };
}

function toIso(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function fromIso(iso: string): Date | undefined {
  if (!ISO_DATE_PATTERN.test(iso)) return undefined;
  const [year, month, day] = iso.split('-').map(Number) as [number, number, number];
  const date = new Date(year, month - 1, day);
  // Rejects a syntactically-valid-but-nonexistent date (e.g. 2026-02-30), which
  // `new Date` would otherwise silently roll over into March.
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day
    ? date
    : undefined;
}

export function DatePicker({ value, onChange, placeholder = 'Select a date', disabled }: DatePickerProps) {
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState(value ?? '');
  const [month, setMonth] = useState<Date>(() => (value && fromIso(value)) || new Date());

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setTyped(value ?? '');
    const parsed = value ? fromIso(value) : undefined;
    if (parsed) setMonth(parsed);
  }, [value]);

  const dayPickerDisabled: Matcher[] | undefined = disabled
    ? [
        ...(disabled.before ? [{ before: disabled.before }] : []),
        ...(disabled.after ? [{ after: disabled.after }] : []),
      ]
    : undefined;

  function select(date: Date | undefined) {
    if (!date) return;
    onChange(toIso(date));
    setOpen(false);
  }

  function onTypedChange(next: string) {
    setTyped(next);
    const parsed = fromIso(next);
    if (!parsed) return;
    // The calendar grid already refuses a disabled day via `dayPickerDisabled`
    // (DayPicker's own `disabled` prop) -- without this check, typing a
    // syntactically valid date directly into the text field bypassed that
    // range entirely (e.g. a future date of death, or a future date of birth).
    if (dayPickerDisabled && dateMatchModifiers(parsed, dayPickerDisabled)) return;
    onChange(next);
    setMonth(parsed);
    setOpen(false);
  }

  function clear(e: React.MouseEvent) {
    e.stopPropagation();
    onChange(null);
    setTyped('');
  }

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <div className="relative">
        <Popover.Trigger asChild>
          <button
            type="button"
            aria-label={value ? formatDate(value) : placeholder}
            className="flex h-9 w-full items-center rounded-md border border-input bg-surface px-2.5 text-left text-sm"
          >
            {value ? (
              <span className="min-w-0 truncate pr-6">{formatDate(value)}</span>
            ) : (
              <span className="min-w-0 truncate text-muted-foreground">{placeholder}</span>
            )}
          </button>
        </Popover.Trigger>
        {value && (
          <button
            type="button"
            aria-label="Clear date"
            onClick={clear}
            className="absolute right-1.5 top-1/2 -translate-y-1/2 rounded p-0.5 text-muted-foreground hover:bg-hover hover:text-foreground"
          >
            <X className="size-3.5" />
          </button>
        )}
      </div>
      <Popover.Portal>
        <Popover.Content
          align="start"
          sideOffset={4}
          className="z-50 rounded-md border border-border bg-surface p-2 shadow-lg"
        >
          <input
            value={typed}
            onChange={(e) => onTypedChange(e.target.value)}
            placeholder="YYYY-MM-DD"
            className="mb-2 h-8 w-full rounded-md border border-input bg-surface px-2 text-sm outline-none"
          />
          <DayPicker
            mode="single"
            selected={value ? fromIso(value) : undefined}
            onSelect={select}
            month={month}
            onMonthChange={setMonth}
            disabled={dayPickerDisabled}
            classNames={{
              month_caption: 'flex items-center justify-center h-8 text-sm font-medium',
              nav: 'flex items-center justify-between',
              button_previous: 'rounded p-1 hover:bg-hover',
              button_next: 'rounded p-1 hover:bg-hover',
              weekday: 'text-muted-foreground text-xs font-normal',
              day_button: 'size-8 rounded-md text-sm hover:bg-hover',
              selected: 'bg-selected rounded-md font-medium',
              today: 'border border-border-strong rounded-md',
              outside: 'text-muted-foreground opacity-50',
              disabled: 'text-muted-foreground opacity-30 pointer-events-none',
            }}
          />
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
