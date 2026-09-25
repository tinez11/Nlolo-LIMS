import type { Gate } from '@/gates/types';
import { cn } from '@/lib/cn';

/**
 * Renders any gate set. One component for every screen's preconditions, so a
 * failing rule looks the same whether it is a claim's coverage or a policy's
 * KYC — and so adding a rule set is a pure function plus nothing.
 *
 * Renders nothing for an empty set: a gate panel appears when there is
 * something to say, not as an empty frame waiting for input.
 */
export function GatePanel({ gates, title }: { gates: Gate[]; title?: string }) {
  if (gates.length === 0) return null;

  const failedHard = gates.some((gate) => !gate.ok && gate.hard);
  const failedSoft = gates.some((gate) => !gate.ok && !gate.hard);

  return (
    <section
      className={cn(
        'rounded-md border p-3',
        failedHard
          ? 'border-status-danger-fg/40 bg-status-danger-bg'
          : failedSoft
            ? 'border-status-warning-fg/40 bg-status-warning-bg'
            : 'border-border bg-surface-muted',
      )}
    >
      {/* Deliberately NO aria-label on this container. `getByLabel` matches by
          case-insensitive SUBSTRING, so a name like "Coverage on the date of
          event" collides with the "Date of event" field it sits under and makes
          that field's locator ambiguous — which broke the claim-intake e2e specs
          the moment this panel was added. The title below is a real element, the
          gate text carries the meaning, and each gate states its own outcome to
          a screen reader, so the container needs no name of its own. */}
      {title && (
        <p className="mb-2 text-xs font-medium tracking-wide text-muted-foreground uppercase">
          {title}
        </p>
      )}
      <ul className="space-y-2">
        {gates.map((gate) => (
          <li key={gate.title} className="flex items-start gap-2.5">
            <span
              className={cn(
                // `leading-none`: the glyph is decorative (aria-hidden) and sits in a 16px
                // circle, where text-xs brings a 16px line box and fills it wall to wall.
                'mt-0.5 grid size-4 shrink-0 place-items-center rounded-full text-xs leading-none font-bold',
                // Glyph colour is the matching `-bg` token, not white: the pair
                // inverts together, so `-fg` is dark in light mode and light in
                // dark mode. `text-white` would be white-on-light in dark mode.
                gate.ok
                  ? 'bg-status-success-fg text-status-success-bg'
                  : gate.hard
                    ? 'bg-status-danger-fg text-status-danger-bg'
                    : 'bg-status-warning-fg text-status-warning-bg',
              )}
              aria-hidden
            >
              {gate.ok ? '✓' : '!'}
            </span>
            <div className="min-w-0">
              <p className="text-sm font-medium first-letter:uppercase">{gate.title}</p>
              <p className="text-xs text-muted-foreground">{gate.detail}</p>
            </div>
            {/* The marker is decorative; the outcome has to reach a screen reader
                as text, not as a colour and a glyph. */}
            <span className="sr-only">{gate.ok ? 'Check passed' : 'Check failed'}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
