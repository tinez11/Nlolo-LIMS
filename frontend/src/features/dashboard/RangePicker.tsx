import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/cn';
import type { MonthRange } from '@/api/dashboard';
import { RANGE_LABEL, rangeFor, type RangeKey } from './range';

/**
 * The months a dashboard covers (2026-10-23): four presets ending this month, and any months the reader names. One
 * row above the figures, so every chart below reads the same range.
 */
export function RangePicker({ value, current, onChange, compareText }: {
  value: { key: RangeKey; range: Required<MonthRange> };
  /** This month, "YYYY-MM": a range may not end after it. */
  current: string;
  onChange: (range: Required<MonthRange>) => void;
  /** What the figures are compared with, said once beside the buttons. */
  compareText?: string;
}) {
  const [customOpen, setCustomOpen] = useState(value.key === 'custom');
  const [from, setFrom] = useState(value.range.from);
  const [to, setTo] = useState(value.range.to);
  const invalid = !from || !to || from > to || to > current;

  const pick = (key: RangeKey) => {
    if (key === 'custom') {
      setCustomOpen(true);
      return;
    }
    setCustomOpen(false);
    onChange(rangeFor(key, current));
  };

  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
      <div role="group" aria-label="Time range" className="inline-flex flex-wrap gap-0.5 rounded-lg bg-control p-0.5">
        {(Object.keys(RANGE_LABEL) as RangeKey[]).map((key) => {
          const on = key === 'custom' ? customOpen || value.key === 'custom' : !customOpen && value.key === key;
          return (
            <button
              key={key}
              type="button"
              aria-pressed={on}
              onClick={() => pick(key)}
              className={cn(
                'rounded-md px-3 py-1.5 text-xs font-semibold text-muted-foreground transition-colors hover:text-foreground',
                on && 'bg-surface text-foreground shadow-card',
              )}
            >
              {RANGE_LABEL[key]}
            </button>
          );
        })}
      </div>
      {customOpen && (
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            if (!invalid) onChange({ from, to });
          }}
        >
          <label className="grid gap-0.5 text-xs text-muted-foreground">
            From
            <Input
              inputSize="sm"
              type="month"
              value={from}
              max={current}
              onChange={(e) => setFrom(e.target.value)}
              className="w-36"
            />
          </label>
          <label className="grid gap-0.5 text-xs text-muted-foreground">
            To
            <Input
              inputSize="sm"
              type="month"
              value={to}
              max={current}
              onChange={(e) => setTo(e.target.value)}
              className="w-36"
            />
          </label>
          <Button type="submit" size="sm" variant="outline" disabled={invalid}>
            Show
          </Button>
        </form>
      )}
      {compareText && <span className="text-xs text-muted-foreground">{compareText}</span>}
    </div>
  );
}
