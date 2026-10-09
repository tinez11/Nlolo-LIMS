import * as Popover from '@radix-ui/react-popover';
import { Calendar, ChevronDown, ChevronLeft, ChevronRight } from 'lucide-react';
import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { DayPicker, dateMatchModifiers, type Matcher } from 'react-day-picker';
import 'react-day-picker/style.css';
import { cn } from '@/lib/cn';
import { ISO_DATE_PATTERN } from '@/lib/patterns';
import { useFieldControl } from './fieldControl';
import { POPOVER_MOTION } from './ui/motion';

/**
 * A date field in the shape of MUI X's DatePicker -- a three-section typed field
 * (`DD/MM/YYYY`) with a trailing calendar button -- built on the stack this app
 * already has (Radix Popover, react-day-picker, Tailwind tokens). Deliberately
 * NOT `@mui/x-date-pickers`: that would bring a second design system and a
 * CSS-in-JS runtime alongside Tailwind v4, and two palettes to keep in step for
 * dark mode.
 *
 * `DD/MM/YYYY` rather than MUI's demo `MM/DD/YYYY`: this platform is Tanzanian
 * (`+255` phone numbers, TZS money), and `03/04/2026` read in the wrong order on
 * a date of event or an invoice due date is a real operational error. Read-only
 * dates elsewhere keep `formatDate`'s unambiguous `Mar 29, 2026` -- the slash
 * format lives only inside this input.
 */

/* -------------------------------------------------------------------- model */

type SectionName = 'day' | 'month' | 'year';

const ORDER = ['day', 'month', 'year'] as const;
const LENGTH: Record<SectionName, number> = { day: 2, month: 2, year: 4 };
const MASK: Record<SectionName, string> = { day: 'DD', month: 'MM', year: 'YYYY' };
const CEILING: Record<SectionName, number> = { day: 31, month: 12, year: 9999 };
/** Offsets into the rendered `DD/MM/YYYY`. Fixed, which is what lets the caret
 *  model be arithmetic instead of parsing -- every section always renders full
 *  width, typed digits or mask letters. */
const RANGE: Record<SectionName, readonly [number, number]> = {
  day: [0, 2],
  month: [3, 5],
  year: [6, 10],
};

const MONTHS_LONG = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
] as const;

const WEEKDAY_LETTERS = ['S', 'M', 'T', 'W', 'T', 'F', 'S'] as const;

/** How far the year grid reaches when `disabled` leaves that end open. */
const YEARS_BACK = 120;
const YEARS_FORWARD = 50;

type Sections = Record<SectionName, string>;

const EMPTY: Sections = { day: '', month: '', year: '' };

/** Typed digits followed by the part of the mask they have not covered: `2D`, `197Y`. */
function renderSection(name: SectionName, digits: string): string {
  return digits ? digits + MASK[name].slice(digits.length) : MASK[name];
}

function render(sections: Sections): string {
  return [
    renderSection('day', sections.day),
    renderSection('month', sections.month),
    renderSection('year', sections.year),
  ].join('/');
}

/** A section left short is zero-padded on exit: `3` -> `03`, and `74` -> `0074`. */
function padSection(name: SectionName, digits: string): string {
  return digits ? digits.padStart(LENGTH[name], '0') : '';
}

/** Pads every section except the one still being typed into. */
function commitSections(raw: Sections, active: SectionName | null): Sections {
  return {
    day: active === 'day' ? raw.day : padSection('day', raw.day),
    month: active === 'month' ? raw.month : padSection('month', raw.month),
    year: active === 'year' ? raw.year : padSection('year', raw.year),
  };
}

function isEmpty(sections: Sections): boolean {
  return !sections.day && !sections.month && !sections.year;
}

/** An ISO date, or null while any section is still short of its full width. */
function toIso(sections: Sections): string | null {
  if (sections.day.length !== 2 || sections.month.length !== 2 || sections.year.length !== 4) {
    return null;
  }
  return `${sections.year}-${sections.month}-${sections.day}`;
}

function sectionsFromIso(iso: string): Sections {
  if (!ISO_DATE_PATTERN.test(iso)) return EMPTY;
  const [year, month, day] = iso.split('-') as [string, string, string];
  return { day, month, year };
}

/**
 * A date built from an explicit year, with no two-digit-year mapping.
 * `new Date(74, 0, 1)` silently means 1974, which would quietly grant the
 * inference the `YYYY` section deliberately refuses -- a date of birth typed as
 * `74` must fail loudly, not become 1974. `setFullYear` takes the year literally.
 */
function makeDate(year: number, monthIndex: number, day: number): Date {
  const date = new Date(2000, 0, 1);
  date.setFullYear(year, monthIndex, day);
  return date;
}

/** Parses an ISO date, rejecting one that does not exist (`2026-02-31`). */
function toDate(iso: string): Date | undefined {
  const [year, month, day] = iso.split('-').map(Number) as [number, number, number];
  const date = makeDate(year, month - 1, day);
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day
    ? date
    : undefined;
}

function sectionAt(position: number): SectionName {
  if (position <= RANGE.day[1]) return 'day';
  if (position <= RANGE.month[1]) return 'month';
  return 'year';
}

/* ---------------------------------------------------------------- component */

export interface DatePickerProps {
  /** ISO yyyy-MM-dd, or null. Same shape a native `<input type="date">` already
   *  produces, so form schemas built around `ISO_DATE_PATTERN` need no change. */
  value: string | null;
  onChange: (isoDate: string | null) => void;
  /** Disallows a range of dates, e.g. no date of event in the future. Also bounds
   *  the year grid, so a forbidden year is never offered in the first place. */
  disabled?: { before?: Date; after?: Date };
}

export function DatePicker({ value, onChange, disabled }: DatePickerProps) {
  const [raw, setRaw] = useState<Sections>(() => (value ? sectionsFromIso(value) : EMPTY));
  const [active, setActive] = useState<SectionName | null>(null);
  /** Has the active section taken a digit since focus entered it? A section that
   *  has not replaces on the first digit rather than appending, so overshooting
   *  an auto-advance is recoverable without reaching for an arrow key. */
  const [dirty, setDirty] = useState(false);
  const [focused, setFocused] = useState(false);
  const [open, setOpen] = useState(false);
  const [yearView, setYearView] = useState(false);
  const [month, setMonth] = useState<Date>(() => (value ? toDate(value) : undefined) ?? new Date());

  const inputRef = useRef<HTMLInputElement>(null);
  const errorId = useId();
  /**
   * The id `FormField`'s `<label htmlFor>` points at, when this sits inside one.
   *
   * Load-bearing, and easy to lose: FormField used to WRAP its child in the
   * label, so this input was named implicitly and needed nothing. Now the label
   * is explicit, so a control that ignores the context has a label pointing at
   * an element that does not exist -- and the field goes from correctly named to
   * anonymous. Caught by the e2e suite (`getByLabel('Date of event')` timing out
   * across eight specs), which is exactly the kind of thing unit tests do not see.
   *
   * Only the id is taken. This component does its own `aria-invalid` for a
   * malformed or out-of-range date, which is a different failure from the
   * schema-level one FormField knows about.
   */
  const { id: fieldId } = useFieldControl();

  const thisYear = new Date().getFullYear();
  const minYear = disabled?.before ? disabled.before.getFullYear() : thisYear - YEARS_BACK;
  const maxYear = disabled?.after ? disabled.after.getFullYear() : thisYear + YEARS_FORWARD;

  const dayPickerDisabled: Matcher[] | undefined = disabled
    ? [
        ...(disabled.before ? [{ before: disabled.before }] : []),
        ...(disabled.after ? [{ after: disabled.after }] : []),
      ]
    : undefined;

  const committed = commitSections(raw, active);
  const iso = toIso(committed);
  const parsed = iso ? toDate(iso) : undefined;
  // The year bounds are enforced here too, not just in the grid: `0074` is a real
  // date that would otherwise slip past an `{after: today}` matcher unnoticed.
  const usable =
    !!parsed &&
    parsed.getFullYear() >= minYear &&
    parsed.getFullYear() <= maxYear &&
    !(dayPickerDisabled && dateMatchModifiers(parsed, dayPickerDisabled));
  const validIso = usable ? iso : null;
  /** Complete, but a date that does not exist or one this field forbids. An
   *  incomplete field is not an error -- it is simply not finished. */
  const invalid = iso !== null && !usable;

  /**
   * Only a usable date is ever reported upward; anything else reports null.
   * Holding the previous value while the field displays a rejected date would let
   * a form submit a stale date the user believes they replaced.
   */
  const reported = useRef<string | null>(value ?? null);

  useEffect(() => {
    if (validIso === reported.current) return;
    reported.current = validIso;
    onChange(validIso);
  }, [validIso, onChange]);

  // Adjusting state during render, not in an effect: this is the documented
  // "a prop changed, so reset state derived from it" case, and it re-renders
  // before committing rather than painting the stale sections first.
  //
  // Only a value that did NOT come from this field resets it -- a locally
  // rejected date is reported upward as null while `raw` still holds the digits,
  // and re-syncing on that would wipe what the user is looking at.
  const incoming = value ?? null;
  if (incoming !== reported.current) {
    reported.current = incoming;
    setRaw(incoming ? sectionsFromIso(incoming) : EMPTY);
    const parsedIncoming = incoming ? toDate(incoming) : undefined;
    if (parsedIncoming) setMonth(parsedIncoming);
  }

  const display = render(committed);

  useLayoutEffect(() => {
    const input = inputRef.current;
    if (!input || !active || document.activeElement !== input) return;
    const [start, end] = RANGE[active];
    if (input.selectionStart !== start || input.selectionEnd !== end) {
      input.setSelectionRange(start, end);
    }
  }, [active, display]);

  function focusSection(name: SectionName) {
    setActive(name);
    setDirty(false);
  }

  function moveSection(delta: number) {
    const index = ORDER.indexOf(active ?? 'day');
    const next = ORDER[Math.min(ORDER.length - 1, Math.max(0, index + delta))];
    if (next) focusSection(next);
  }

  function typeDigit(digit: string) {
    const name = active ?? 'day';
    let next = (dirty ? raw[name] : '') + digit;
    // Overflowing the width, or the highest value the section can hold, starts
    // the section over on this digit -- `4` then `5` in the day section is a 5th,
    // not an impossible 45th.
    if (next.length > LENGTH[name] || Number(next) > CEILING[name]) next = digit;
    setRaw({ ...raw, [name]: next });
    if (next.length === LENGTH[name]) {
      // Advance only once the section is full. Never on a merely impossible next
      // digit, so `3` in the day section still waits for its `0` or `1`.
      moveSection(1);
      return;
    }
    setActive(name);
    setDirty(true);
  }

  function step(delta: number) {
    const name = active ?? 'day';
    const current = committed[name] ? Number(committed[name]) : null;
    let next: number;
    if (name === 'year') {
      next = Math.min(maxYear, Math.max(minYear, (current ?? thisYear) + delta));
    } else if (current === null) {
      // An empty section starts from today rather than from the delta, so one
      // press of the arrow key lands somewhere meaningful.
      const today = new Date();
      next = name === 'day' ? today.getDate() : today.getMonth() + 1;
    } else {
      const ceiling = CEILING[name];
      next = ((current - 1 + delta + ceiling) % ceiling) + 1;
    }
    setRaw({ ...raw, [name]: String(next).padStart(LENGTH[name], '0') });
    setActive(name);
    setDirty(false);
  }

  function clearSection() {
    const input = inputRef.current;
    if (input && input.selectionStart === 0 && input.selectionEnd === input.value.length) {
      // Select-all then delete clears the whole field -- there is no clear button.
      setRaw(EMPTY);
      focusSection('day');
      return;
    }
    const name = active ?? 'day';
    if (!raw[name]) {
      moveSection(-1);
      return;
    }
    setRaw({ ...raw, [name]: '' });
    setActive(name);
    setDirty(false);
  }

  /**
   * A write that did not come from a keystroke.
   *
   * Real typing is intercepted in `onKeyDown` and prevented, so it never produces
   * an input event -- which means anything arriving here set the value directly:
   * Playwright's `fill()`, browser autofill, or a mobile IME committing a
   * composition. Ignoring them is what broke 20 call sites across 11 e2e specs
   * when this component was rewritten, and mobile IME would have followed.
   *
   * Only a complete date is adopted. A partial write is dropped rather than
   * half-applied, because a writer that set `29/08` meant a date, not a day.
   */
  function adopt(written: string) {
    if (written === '') {
      setRaw(EMPTY);
      setActive(null);
      setDirty(false);
      return;
    }
    const digits = written.replace(/\D/g, '');
    if (digits.length !== LENGTH.day + LENGTH.month + LENGTH.year) return;
    setRaw({
      day: digits.slice(0, 2),
      month: digits.slice(2, 4),
      year: digits.slice(4),
    });
    setActive(null);
    setDirty(false);
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Tab' || event.altKey || event.ctrlKey || event.metaKey) return;
    if (/^\d$/.test(event.key)) {
      event.preventDefault();
      typeDigit(event.key);
      return;
    }
    switch (event.key) {
      case 'ArrowLeft':
        event.preventDefault();
        moveSection(-1);
        return;
      case 'ArrowRight':
        event.preventDefault();
        moveSection(1);
        return;
      case '/':
        event.preventDefault();
        // The separator is what a person types *after* a section's digits, so on
        // a section that is still empty it means "I am already here" -- typing
        // `29/3/` must land the 3 in the month, not skip past it into the year
        // because the 29 had already advanced on its own.
        if (raw[active ?? 'day']) moveSection(1);
        return;
      case 'ArrowUp':
        event.preventDefault();
        step(1);
        return;
      case 'ArrowDown':
        event.preventDefault();
        step(-1);
        return;
      case 'Backspace':
      case 'Delete':
        event.preventDefault();
        clearSection();
        return;
      case 'Escape':
        return;
      default:
        // Nothing else may reach the value. A stray character would desync the
        // fixed offsets the section model depends on.
        if (event.key.length === 1) event.preventDefault();
    }
  }

  function onOpenChange(next: boolean) {
    setOpen(next);
    if (!next) {
      setYearView(false);
      return;
    }
    // Land on what has been typed when there is enough of it -- typing `03/1974`
    // and reaching for the calendar is how a date of birth actually gets entered.
    const typedYear = committed.year.length === 4 ? Number(committed.year) : null;
    const typedMonth = committed.month.length === 2 ? Number(committed.month) : null;
    if (typedYear !== null && typedMonth !== null && typedMonth >= 1 && typedMonth <= 12) {
      setMonth(monthStart(Math.min(maxYear, Math.max(minYear, typedYear)), typedMonth - 1));
      return;
    }
    const fromValue = value ? toDate(value) : undefined;
    setMonth(parsed ?? fromValue ?? new Date());
  }

  function selectDay(date: Date | undefined) {
    if (!date) return;
    setRaw({
      day: String(date.getDate()).padStart(2, '0'),
      month: String(date.getMonth() + 1).padStart(2, '0'),
      year: String(date.getFullYear()).padStart(4, '0'),
    });
    setActive(null);
    setDirty(false);
    setOpen(false);
  }

  function selectYear(year: number) {
    setMonth(monthStart(year, month.getMonth()));
    setYearView(false);
  }

  function shiftMonth(delta: number) {
    setMonth(monthStart(month.getFullYear(), month.getMonth() + delta));
  }

  const canGoBack = month.getFullYear() > minYear || month.getMonth() > 0;
  const canGoForward = month.getFullYear() < maxYear || month.getMonth() < 11;
  const selectedYear = committed.year.length === 4 ? Number(committed.year) : month.getFullYear();

  return (
    <Popover.Root open={open} onOpenChange={onOpenChange}>
      {/* Anchored to the whole field rather than to the trigger, so the calendar
          hangs off the field's left edge as MUI's does -- anchoring to the icon
          button alone pushes it out to the right of the input. */}
      <Popover.Anchor
        className={cn(
          'flex h-10 items-center rounded-md border bg-surface pl-3 pr-1',
          invalid ? 'border-status-danger-fg' : 'border-input',
          !invalid && !focused && 'hover:border-border-strong',
          // A hairline border plus a 1px ring reads as MUI's doubled focus
          // outline without the 1px reflow a border-width change would cause.
          focused &&
            (invalid ? 'ring-1 ring-status-danger-fg' : 'border-foreground ring-1 ring-foreground'),
        )}
      >
        <input
          ref={inputRef}
          {...(fieldId ? { id: fieldId } : {})}
          value={display}
          // Keystrokes are handled in onKeyDown and prevented, so this only ever
          // fires for a write that bypassed the keyboard -- see `adopt`.
          onChange={(event) => adopt(event.target.value)}
          onKeyDown={onKeyDown}
          onFocus={() => {
            setFocused(true);
            if (!active) focusSection('day');
          }}
          onMouseUp={(event) => focusSection(sectionAt(event.currentTarget.selectionStart ?? 0))}
          onBlur={() => {
            setFocused(false);
            setActive(null);
            setDirty(false);
          }}
          inputMode="numeric"
          autoComplete="off"
          spellCheck={false}
          aria-invalid={invalid || undefined}
          aria-describedby={invalid ? errorId : undefined}
          className={cn(
            'w-full bg-transparent text-sm outline-none focus-visible:outline-none',
            isEmpty(committed) && 'text-muted-foreground',
          )}
        />
        <Popover.Trigger asChild>
          <button
            type="button"
            aria-label="Choose date"
            className="rounded-full p-1.5 text-muted-foreground hover:bg-hover hover:text-foreground"
          >
            <Calendar className="size-4" />
          </button>
        </Popover.Trigger>
      </Popover.Anchor>

      {invalid && (
        <p id={errorId} role="alert" className="mt-1 text-xs text-status-danger-fg">
          {parsed ? 'Date outside the allowed range' : 'Not a valid date'}
        </p>
      )}

      <Popover.Portal>
        <Popover.Content
          align="start"
          sideOffset={6}
          className={cn('z-50 w-71 rounded-md border border-border bg-surface p-2 shadow-lg', POPOVER_MOTION)}
        >
          <div className="flex items-center justify-between">
            <button
              type="button"
              onClick={() => setYearView((shown) => !shown)}
              aria-expanded={yearView}
              className="flex items-center gap-1 rounded-md px-2 py-1 text-sm font-medium hover:bg-hover"
            >
              {MONTHS_LONG[month.getMonth()]} {month.getFullYear()}
              <ChevronDown className={cn('size-4 transition-transform', yearView && 'rotate-180')} />
            </button>
            {!yearView && (
              <div className="flex items-center">
                <button
                  type="button"
                  aria-label="Previous month"
                  disabled={!canGoBack}
                  onClick={() => shiftMonth(-1)}
                  className="rounded-full p-1.5 hover:bg-hover disabled:pointer-events-none disabled:opacity-30"
                >
                  <ChevronLeft className="size-4" />
                </button>
                <button
                  type="button"
                  aria-label="Next month"
                  disabled={!canGoForward}
                  onClick={() => shiftMonth(1)}
                  className="rounded-full p-1.5 hover:bg-hover disabled:pointer-events-none disabled:opacity-30"
                >
                  <ChevronRight className="size-4" />
                </button>
              </div>
            )}
          </div>

          {yearView ? (
            <YearGrid
              minYear={minYear}
              maxYear={maxYear}
              selectedYear={selectedYear}
              onSelect={selectYear}
            />
          ) : (
            <DayPicker
              mode="single"
              selected={validIso ? toDate(validIso) : undefined}
              onSelect={selectDay}
              month={month}
              onMonthChange={setMonth}
              disabled={dayPickerDisabled}
              formatters={{ formatWeekdayName: (date) => WEEKDAY_LETTERS[date.getDay()] ?? '' }}
              classNames={{
                // The month caption and nav are rendered above instead, so the
                // year view can replace the grid without the header moving.
                month_caption: 'hidden',
                nav: 'hidden',
                months: 'mt-1',
                weekday: 'size-9 text-xs font-normal text-muted-foreground',
                day: 'p-0',
                day_button: 'size-9 rounded-full text-sm hover:bg-hover',
                selected: '[&_button]:bg-accent [&_button]:text-accent-foreground [&_button]:font-medium',
                today: '[&_button]:border [&_button]:border-border-strong',
                outside: 'text-muted-foreground opacity-40',
                disabled: 'text-muted-foreground opacity-30 pointer-events-none',
              }}
            />
          )}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}

function monthStart(year: number, monthIndex: number): Date {
  return makeDate(year, monthIndex, 1);
}

/**
 * The year view. `react-day-picker` has no equivalent, so this replaces its grid
 * outright. It exists for date of birth: reaching 1974 through the month arrows
 * is upwards of 600 clicks.
 */
function YearGrid({
  minYear,
  maxYear,
  selectedYear,
  onSelect,
}: {
  minYear: number;
  maxYear: number;
  selectedYear: number;
  onSelect: (year: number) => void;
}) {
  const selectedRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    // jsdom does not implement scrollIntoView at all, hence the optional call.
    selectedRef.current?.scrollIntoView?.({ block: 'center' });
  }, []);

  const years: number[] = [];
  for (let year = minYear; year <= maxYear; year += 1) years.push(year);

  return (
    <div className="mt-1 h-63 overflow-y-auto">
      <div className="grid grid-cols-3 gap-1 pr-1">
        {years.map((year) => (
          <button
            key={year}
            type="button"
            ref={year === selectedYear ? selectedRef : undefined}
            onClick={() => onSelect(year)}
            className={cn(
              'rounded-full py-1.5 text-sm hover:bg-hover',
              year === selectedYear && 'bg-accent font-medium text-accent-foreground hover:bg-accent',
            )}
          >
            {year}
          </button>
        ))}
      </div>
    </div>
  );
}
