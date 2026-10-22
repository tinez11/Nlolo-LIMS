import type { ReactNode } from 'react';
import { FormOutline } from '@/components/FormOutline';

/**
 * A full-page form: the form on a card at a readable width, and a rail beside it (2026-10-22).
 *
 * Every create/propose screen used to set its form straight on the page at `max-w-xl` or
 * `max-w-2xl`, pinned left, so on a desk monitor half the screen beside it stayed empty -- easy to
 * miss on a white page, plain to see once the page ground took a tint and records became cards.
 *
 * The rail is the form's own map (`FormOutline`, reading the `data-outline` sections inside
 * `#formId`; it draws nothing below two sections) and, where the page has one, `aside`: a figure
 * the page already derives as the form fills in, never a restatement of the description. Below
 * `lg` the rail is hidden and the card takes the full width, so a phone loses nothing it needs --
 * whatever `aside` shows must also be in the form itself.
 */
export function FormLayout({
  formId,
  outlineLabel,
  aside,
  card = true,
  children,
}: {
  /** The id the card carries, which `FormOutline` reads its sections from. */
  formId: string;
  /** The outline's accessible name -- "Scheme proposal sections". */
  outlineLabel: string;
  aside?: ReactNode;
  /**
   * False for a form whose sections are already `Panel`s: a card around cards is a box in a box.
   * Those panels mark themselves for the outline with `Panel outline`.
   */
  card?: boolean;
  children: ReactNode;
}) {
  return (
    <div className="grid items-start gap-6 px-6 pb-8 lg:grid-cols-[minmax(0,48rem)_16rem]">
      <div
        id={formId}
        className={card ? 'min-w-0 rounded-xl border border-border bg-surface p-6 shadow-card' : 'min-w-0'}
      >
        {children}
      </div>
      {/* Stuck at the bar's own height, not below it: any offset also applies at rest, and set the
          rail lower than the card it sits beside. */}
      <aside className="sticky top-[var(--pagebar-h,0px)] hidden space-y-5 lg:block">
        {aside}
        <FormOutline containerId={formId} label={outlineLabel} />
      </aside>
    </div>
  );
}
