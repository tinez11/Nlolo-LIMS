import { Check, ChevronRight, Shield } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { CustomerPolicySummary } from '@/api/portal';
import { cn } from '@/lib/cn';
import { money, policyStatusTone } from './customerText';
import { CATEGORY_ICON } from './needIcons';
import { useWords } from './words';

/** A policy's state in the customer's words, tinted good, needing attention, or neither. */
export function StatusPill({ status }: { status: string }) {
  const w = useWords().portal;
  const tone = policyStatusTone(status);
  return (
    <span className={cn('shrink-0 rounded-full px-2.5 py-0.5 text-xs font-semibold',
      tone === 'good' && 'bg-status-success-bg text-status-success-fg',
      tone === 'warn' && 'bg-status-warning-bg text-status-warning-fg',
      tone === 'plain' && 'bg-status-neutral-bg text-status-neutral-fg')}>
      {w.status[status] ?? status.toLowerCase().replaceAll('_', ' ')}
    </span>
  );
}

/**
 * One policy as a card (2026-10-29): what it is, its state, and the figure it exists for -- the cover, or what a savings
 * plan is worth -- with what it costs beneath. The whole card opens the policy.
 */
export function PolicyCard({ policy: p }: { policy: CustomerPolicySummary }) {
  const w = useWords().portal;
  const Icon = (p.productCategory && CATEGORY_ICON[p.productCategory]) || Shield;
  const worth = p.value != null;
  const figure = worth ? p.value : p.sumAssured;
  return (
    <Link to={`/customers/policies/${encodeURIComponent(p.policyNumber)}`}
      className="flex gap-4 rounded-2xl border border-border bg-surface p-4 shadow-card transition-colors hover:bg-hover">
      <span className="grid size-12 shrink-0 place-items-center rounded-xl bg-selected text-accent" aria-hidden><Icon className="size-6" /></span>
      <span className="min-w-0 flex-1 space-y-1">
        <span className="flex flex-wrap items-center justify-between gap-2">
          <span className="text-title">{p.productName ?? (p.productCategory ? w.category[p.productCategory] : '') ?? p.policyNumber}</span>
          <StatusPill status={p.status} />
        </span>
        <span className="block text-sm text-muted-foreground">
          {p.productCategory ? w.category[p.productCategory] ?? '' : ''} · <span className="font-mono text-xs">{p.policyNumber}</span>
        </span>
        <span className="flex flex-wrap items-end justify-between gap-x-4 gap-y-1 pt-1">
          {figure != null && Number(figure) > 0 && (
            <span>
              <span className="block text-xs text-muted-foreground">{worth ? w.home.worth : w.home.cover}</span>
              <span className="block text-display tabular-nums">{money(figure, p.currency)}</span>
            </span>
          )}
          {p.premium != null && (
            <span className="text-sm text-muted-foreground tabular-nums">
              {w.home.premium} {money(p.premium, p.currency)} {w.per[p.premiumFrequency ?? ''] ?? ''}
            </span>
          )}
        </span>
      </span>
      <ChevronRight className="mt-3 size-5 shrink-0 self-start text-muted-foreground" aria-hidden />
    </Link>
  );
}

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
