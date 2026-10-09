import * as TabsPrimitive from '@radix-ui/react-tabs';
import type { ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';

export interface TabDef {
  value: string;
  label: string;
  /** Only a count the page ALREADY HOLDS. Never fetch to fill a tab label. */
  count?: number;
  content: ReactNode;
}

/**
 * The sections of a record page, one at a time.
 *
 * For records with many independent registers -- a policy's beneficiaries, invoices, loans,
 * messages and cessions are separate jobs done by separate people -- where one long stack
 * made the fourth panel a scroll away and the header actions scroll off. Not for a claim:
 * an assessor reads the evidence WHILE writing findings, so the claim page keeps one scroll
 * and a SectionNav.
 *
 * The tab is in the URL, so Back works, a tab can be linked to, and a test can open one
 * directly. The first tab is the one the page exists to act on, and carries no parameter.
 * Inactive tabs are unmounted (Radix's default), so a tab's data loads when it is opened.
 */
export function RecordTabs({
  tabs,
  label,
  param = 'tab',
}: {
  tabs: TabDef[];
  label: string;
  param?: string;
}) {
  const [params, setParams] = useSearchParams();
  const first = tabs[0]?.value ?? '';
  const requested = params.get(param);
  const active = tabs.some((tab) => tab.value === requested) ? (requested as string) : first;

  return (
    <TabsPrimitive.Root
      value={active}
      onValueChange={(value) => {
        setParams(
          (current) => {
            const next = new URLSearchParams(current);
            if (value === first) next.delete(param);
            else next.set(param, value);
            return next;
          },
          // Switching tab is not a new place, it is the same record seen differently: a
          // Back button that walked every tab a person glanced at would bury the list they
          // came from.
          { replace: true },
        );
      }}
    >
      <TabsPrimitive.List
        aria-label={label}
        // No horizontal padding of its own: this mounts INSIDE the caller's layout -- on the
        // policy record, inside `DetailLayout`'s own `px-6` grid -- and carrying a second
        // gutter put the tabs 48px from the edge while the record rail beside them sat at
        // 24px, with the sticky rule stopping short of both. The caller owns the gutter; the
        // bar owns only its stickiness and its rule.
        //
        // The rule is an inset shadow, not a `border-b`, so a trigger's 2px underline can lie ON
        // it without leaving the strip. It used to be a border with each trigger pulled `-mb-px`
        // over it -- and since `overflow-x-auto` computes the vertical axis to `auto` as well,
        // that overhanging pixel grew a vertical scrollbar: up/down arrows at the end of every
        // record's tabs. `overflow-y-hidden` stays as the backstop.
        className="sticky top-[var(--pagebar-h,0px)] z-10 flex gap-5 overflow-x-auto overflow-y-hidden bg-background shadow-[inset_0_-1px_0_var(--color-border)]"
      >
        {tabs.map((tab) => (
          <TabsPrimitive.Trigger
            key={tab.value}
            value={tab.value}
            aria-label={tab.count === undefined ? undefined : `${tab.label}, ${tab.count} items`}
            // Inactive tabs are --muted-foreground (~7:1), not Espresso's own text-light,
            // which measures 4.17:1 on white and fails AA at this size.
            // The focus ring is drawn INSIDE the trigger: the strip clips both axes, so the
            // global 2px-outside ring would be cut off above and below.
            className="inline-flex min-h-10 focus-visible:-outline-offset-2 shrink-0 items-center gap-1.5 border-b-2 border-transparent text-sm text-muted-foreground transition-colors hover:text-foreground data-[state=active]:border-foreground data-[state=active]:font-medium data-[state=active]:text-foreground pointer-coarse:min-h-11"
          >
            {tab.label}
            {tab.count !== undefined && (
              <span className="rounded-full bg-control px-1.5 text-xs tabular-nums" aria-hidden>
                {tab.count}
              </span>
            )}
          </TabsPrimitive.Trigger>
        ))}
      </TabsPrimitive.List>
      {tabs.map((tab) => (
        <TabsPrimitive.Content
          key={tab.value}
          value={tab.value}
          className="focus-visible:outline-none"
        >
          {tab.content}
        </TabsPrimitive.Content>
      ))}
    </TabsPrimitive.Root>
  );
}
