import type { ReceivablesAging as Aging } from '@/api/dashboard';
import { BucketBars } from '@/components/charts/BucketBars';
import { countValue } from '@/lib/counts';
import { formatMoney } from '@/lib/money';

/** The working currency. A dashboard shows this one; any other is named, never added in. */
const WORKING_CURRENCY = 'TZS';

/**
 * The five age bands, in the order a statement reads them, with the words a collections officer uses.
 *
 * <p>Driven by this list rather than by the order the server sent, so a band the server omits still appears: an
 * empty band means "nothing is this overdue", which is the answer, not a gap.
 */
type BandName = Aging['buckets'][number]['name'];

const BANDS: { name: BandName; label: string }[] = [
  { name: 'NOT_DUE', label: 'Not due' },
  { name: 'D1_30', label: '1–30 days' },
  { name: 'D31_60', label: '31–60 days' },
  { name: 'D61_90', label: '61–90 days' },
  { name: 'OVER_90', label: 'Over 90 days' },
];

/**
 * What is owed, by how overdue it is (phase 2, 2026-10-15).
 *
 * <b>A list, not a chart.</b> Five labelled rows with a count and an amount. Phase 3 adds the bars; until then a
 * reader gets the figures, which is the part that matters.
 *
 * <b>No colour.</b> An age is not a state: the six status hues stay reserved for things that have a status, and an
 * "over 90 days" row painted red would be the Stamp Rule broken (DESIGN.md).
 *
 * <b>Nothing is added across currencies.</b> The TZS entry is shown and any other is named underneath, because a
 * reader who thinks they are looking at the whole book when they are not is worse served than one told plainly.
 */
export function ReceivablesAging({ aging }: { aging: Aging[] }) {
  const working = aging.find((a) => a.currency === WORKING_CURRENCY);
  const others = aging.filter((a) => a.currency !== WORKING_CURRENCY).map((a) => a.currency);

  if (!working && others.length === 0) {
    return <p className="px-4 pb-4 text-xs text-muted-foreground">Nothing is owed.</p>;
  }

  const byName = new Map((working?.buckets ?? []).map((b) => [b.name, b]));

  // The bars show the SHAPE of the book; the list below gives the figures. A bar cannot be read to the shilling,
  // and a list cannot be read at a glance, so both stay.
  const bars = BANDS.map(({ name, label }) => {
    const bucket = byName.get(name);
    return {
      label,
      value: bucket ? Number(bucket.outstanding?.amount ?? 0) : 0,
      display: bucket ? formatMoney(bucket.outstanding) : `${WORKING_CURRENCY} 0.00`,
      count: bucket?.cases ?? 0,
    };
  });

  return (
    <div className="pb-1">
      {working && (
        <div className="px-4 pb-3">
          <BucketBars
            buckets={bars}
            description={`Receivables by age, in ${WORKING_CURRENCY}. ${bars
              .map((b) => `${b.label}: ${b.display}`)
              .join('. ')}.`}
          />
        </div>
      )}
      <ul className="divide-y divide-border">
        {BANDS.map(({ name, label }) => {
          const bucket = byName.get(name);
          return (
            <li key={name} className="flex items-baseline gap-4 px-4 py-2">
              <span className="min-w-0 flex-1 text-sm">{label}</span>
              <span className="w-16 shrink-0 text-right text-xs text-muted-foreground">
                {bucket ? `${countValue(bucket.cases)} ${bucket.cases === 1 ? 'case' : 'cases'}` : '—'}
              </span>
              <span className="w-40 shrink-0 text-right text-sm whitespace-nowrap">
                {bucket ? formatMoney(bucket.outstanding) : `${WORKING_CURRENCY} 0.00`}
              </span>
            </li>
          );
        })}
      </ul>
      {others.length > 0 && (
        <p className="px-4 pt-2 text-xs text-muted-foreground">
          There is also owed in {others.join(', ')}, not shown here and not added in: a total across currencies
          would be a number in no currency.
        </p>
      )}
    </div>
  );
}
