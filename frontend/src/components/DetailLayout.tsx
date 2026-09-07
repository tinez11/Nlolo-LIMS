import type { ReactNode } from 'react';

/**
 * The two-column body of every detail page: the work, and the record it is about.
 *
 * Extracted rather than copy-pasted, because the grid it replaces was already
 * written out longhand in eight feature files -- the same story as `Panel`,
 * `Field` and `FormField` before it, and the reason the sticky behaviour below
 * could not have been added eight times consistently.
 *
 * WHAT GOES WHERE is the whole point of the component, and it is not a preference:
 *
 * - **`record` is facts, and it is pinned.** Who, what, how much, as-of-when. It
 *   sticks while the main column scrolls, because an assessor writing findings
 *   needs the claimant's name on screen the entire time -- the previous layout let
 *   it scroll away and the answer was a second browser tab.
 * - **`children` is the work**, ordered act-first where the page exists to perform
 *   an act, then the registers that evidence it.
 * - **No mutating action goes in `record`.** Not because it would not fit, but
 *   because PRODUCT.md principle 4 is that acting is deliberate: a decision belongs
 *   in the column a person is reading, at full width, not in a 320px margin they
 *   have learned to treat as a summary.
 *
 * Below `lg` the grid collapses and the record falls after the work. That is the
 * right order for a narrow viewport -- the person came to do the job, and the
 * identifying name and status are already in `PageHeader` above -- and it needs no
 * `order` utilities, so DOM order still matches visual order for keyboard and
 * assistive tech at every width.
 */
export function DetailLayout({ record, children }: { record?: ReactNode; children: ReactNode }) {
  return (
    <div className="grid gap-5 px-6 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
      <div className="space-y-5">{children}</div>

      {record && (
        /* `self-start` is what makes `sticky` mean anything in a grid: without it the
           column stretches to the row's full height and has no slack to stick within.
           The height cap plus `overflow-y-auto` is for the records that are genuinely
           taller than a screen -- a client with identity, person and address panels --
           where a sticky element taller than the viewport would otherwise pin its top
           and put its last rows permanently out of reach. `overscroll-contain` stops
           that inner scroll from chaining into the page once it bottoms out. */
        <div className="space-y-5 lg:sticky lg:top-6 lg:max-h-[calc(100dvh-3rem)] lg:self-start lg:overflow-y-auto lg:overscroll-contain lg:[scrollbar-gutter:stable]">
          {record}
        </div>
      )}
    </div>
  );
}
