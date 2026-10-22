import * as TabsPrimitive from '@radix-ui/react-tabs';
import { useLayoutEffect, useRef, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { revealIfHidden } from '@/lib/reveal';

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

  const root = useRef<HTMLDivElement>(null);
  const arrived = useRef(false);

  /*
    Open a tab from halfway down a record and you used to stay exactly where you
    were. The panel you were reading is unmounted (see the note above), so the new
    one mounts at the TOP of a container you are scrolled past -- and the sticky
    strip goes with it, because a sticky element can only stick inside its own box
    and that box is the tabs, not the page. On a `DetailLayout` record the tabs sit
    beside a record rail that is often much taller, so a short panel ends well above
    the fold: Reinsurance on a policy left the reader at the bottom of the rail,
    seeing neither the section they asked for nor the tabs they asked with.

    AFTER the new panel has committed, not in `onValueChange`. Scrolling first means
    scrolling against the outgoing panel's height, and the browser then clamps the
    position when the shorter one lands -- which reproduces the bug it is meant to
    fix. A layout effect runs after commit and before paint, so the correction is
    never visible as a jump.

    Not on arrival. A deep link and a first paint are someone ARRIVING, and moving a
    page nobody moved is its own bug.

    Instant, not smooth. `FormOutline` scrolls smoothly because it jumps WITHIN a
    form you are reading and the continuity is the point; here the content is
    replaced outright, so there is no spatial thread to preserve and an animation
    only delays the thing you asked for.
  */
  useLayoutEffect(() => {
    if (!arrived.current) {
      arrived.current = true;
      return;
    }
    // Only when the strip has scrolled out of sight: a tab clicked in plain view must not move the page (2026-10-22).
    revealIfHidden(root.current);
  }, [active]);

  return (
    <TabsPrimitive.Root
      ref={root}
      // Lands the strip below the sticky page bar instead of beneath it, using the same
      // expression the strip's own `sticky top-[...]` uses, so the two cannot drift apart.
      // A record with tabs never also has a section bar, so the page bar is the whole offset.
      className="scroll-mt-[var(--pagebar-h,0px)]"
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
        //
        // The triggers sit in a pill track (the Tinezz direction): the strip stays full width and
        // opaque so content scrolling under it never shows beside a narrow track.
        className="sticky top-[var(--pagebar-h,0px)] z-10 flex overflow-x-auto overflow-y-hidden bg-background py-2"
      >
        <div className="flex w-fit shrink-0 gap-1 rounded-xl bg-control p-1">
        {tabs.map((tab) => (
          <TabsPrimitive.Trigger
            key={tab.value}
            value={tab.value}
            aria-label={tab.count === undefined ? undefined : `${tab.label}, ${tab.count} items`}
            // Inactive tabs are --muted-foreground (~7:1), not Espresso's own text-light,
            // which measures 4.17:1 on white and fails AA at this size.
            // The focus ring is drawn INSIDE the trigger: the strip clips both axes, so the
            // global 2px-outside ring would be cut off above and below.
            className="inline-flex min-h-8 focus-visible:-outline-offset-2 shrink-0 items-center gap-1.5 rounded-lg px-3.5 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground data-[state=active]:bg-surface data-[state=active]:text-foreground data-[state=active]:shadow-raise pointer-coarse:min-h-11"
          >
            {tab.label}
            {tab.count !== undefined && (
              <span className="rounded-full bg-control-hover px-1.5 text-xs tabular-nums" aria-hidden>
                {tab.count}
              </span>
            )}
          </TabsPrimitive.Trigger>
        ))}
        </div>
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
