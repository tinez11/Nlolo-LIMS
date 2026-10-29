import { Check } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { useWords } from './words';

/**
 * The customer's self-service pieces (2026-10-29): big answers a thumb can hit, one question to a screen, and the line
 * an application travels. Sized for a phone and a first-time buyer -- 16px words and 48px targets -- where the staff
 * console stays compact.
 */

/** One answer to a question: a radio, drawn as a card. */
export function Choice({ label, note, selected, onSelect, icon }: {
  label: string;
  note?: string | undefined;
  selected: boolean;
  onSelect: () => void;
  icon?: ReactNode;
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      onClick={onSelect}
      className={cn(
        'flex min-h-14 w-full items-center gap-3 rounded-xl border bg-surface px-4 py-3 text-left transition-colors',
        selected
          ? 'border-2 border-accent bg-selected shadow-[0_0_0_4px_color-mix(in_oklab,var(--accent)_14%,transparent)]'
          : 'border-border-strong hover:bg-hover',
      )}
    >
      {icon && <span className={cn('grid size-10 shrink-0 place-items-center rounded-lg', selected ? 'bg-accent text-accent-foreground' : 'bg-control text-foreground')} aria-hidden>{icon}</span>}
      <span className="min-w-0 flex-1">
        <span className="block text-base font-semibold tabular-nums">{label}</span>
        {note && <span className="block text-sm text-muted-foreground">{note}</span>}
      </span>
      <span aria-hidden className={cn('grid size-6 shrink-0 place-items-center rounded-full border-2', selected ? 'border-accent bg-accent text-accent-foreground' : 'border-border-strong')}>
        {selected && <Check className="size-3.5" strokeWidth={3} />}
      </span>
    </button>
  );
}

/** A question's screen: what is asked, a line of help, and the answers. */
export function Question({ title, help, icon, children }: { title: string; help?: string; icon?: ReactNode; children: ReactNode }) {
  return (
    <section aria-labelledby="question-title" className="space-y-4">
      {icon && <span className="grid size-14 place-items-center rounded-2xl bg-selected text-accent" aria-hidden>{icon}</span>}
      <div className="space-y-1.5">
        <h2 id="question-title" className="text-headline">{title}</h2>
        {help && <p className="text-base text-muted-foreground">{help}</p>}
      </div>
      {children}
    </section>
  );
}

/** "2 of 4" and a bar of segments, for a flow of a few screens. */
export function StepBar({ at, of, label }: { at: number; of: number; label: string }) {
  return (
    <div className="space-y-2">
      <p className="text-sm text-muted-foreground tabular-nums">{label}</p>
      <div className="grid gap-1.5" style={{ gridTemplateColumns: `repeat(${of}, minmax(0, 1fr))` }} aria-hidden>
        {Array.from({ length: of }, (_, i) => (
          <span key={i} className={cn('h-1.5 rounded-full', i <= at ? 'bg-accent' : 'bg-control-press')} />
        ))}
      </div>
    </div>
  );
}

/**
 * Where an application is, as a line of five steps: done steps ticked, the one in progress marked, the rest waiting.
 * A list in reading order, so a screen reader hears it as the steps it is.
 */
export function ProgressLine({ at, tone }: { at: number; tone: 'go' | 'wait' | 'stop' }) {
  const w = useWords().progress;
  const steps = tone === 'stop' || tone === 'wait' && at === 2 ? w.steps.slice(0, 3) : w.steps;
  return (
    <ol className="grid gap-2" style={{ gridTemplateColumns: `repeat(${steps.length}, minmax(0, 1fr))` }}>
      {steps.map((step, i) => {
        const done = i < at;
        const now = i === at;
        return (
          <li key={step} className="min-w-0" aria-current={now ? 'step' : undefined}>
            <span className="flex items-center gap-1">
              <span
                className={cn(
                  'grid size-6 shrink-0 place-items-center rounded-full text-xs font-bold',
                  done && 'bg-accent text-accent-foreground',
                  now && tone === 'go' && 'border-2 border-accent bg-surface text-accent',
                  now && tone === 'wait' && 'bg-status-warning-bg text-status-warning-fg',
                  now && tone === 'stop' && 'bg-status-danger-bg text-status-danger-fg',
                  !done && !now && 'bg-control text-muted-foreground',
                )}
              >
                {done ? <Check className="size-3.5" strokeWidth={3} aria-hidden /> : i + 1}
              </span>
              {i < steps.length - 1 && <span aria-hidden className={cn('h-0.5 flex-1 rounded', done ? 'bg-accent' : 'bg-control-press')} />}
            </span>
            <span className={cn('mt-1 block text-xs leading-tight', now ? 'font-semibold text-foreground' : 'text-muted-foreground')}>
              {step}
              {done && <span className="sr-only"> — done</span>}
              {now && <span className="sr-only"> — now</span>}
            </span>
          </li>
        );
      })}
    </ol>
  );
}
