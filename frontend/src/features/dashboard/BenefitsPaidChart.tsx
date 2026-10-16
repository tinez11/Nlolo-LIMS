import type { SettlementMonth } from '@/api/dashboard';
import { BarChart, type Series } from '@/components/charts/BarChart';
import { formatMoney } from '@/lib/money';

/** The working currency. The chart draws this one; any other is named, never added in. */
const WORKING_CURRENCY = 'TZS';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const monthLabel = (month: string) => `${MONTHS[Number(month.slice(5, 7)) - 1] ?? month} ${month.slice(0, 4)}`;

/**
 * Benefits paid by month -- the approved amounts of claims settled (2026-10-16).
 *
 * <p><b>One currency only, and it says which.</b> Adding a USD settlement into a TZS bar would be a number in no
 * currency, so the other currencies are named underneath with their claim counts instead. A reader who believes a
 * bar is the whole month when it is not is worse served than one told plainly.
 *
 * <p>No arithmetic: each bar is one month's total exactly as claims computed it. A month with no settlements is
 * absent from the series, so the chart draws a gap rather than a zero.
 */
export function BenefitsPaidChart({
  settlements,
  months,
}: {
  settlements: SettlementMonth[];
  months: string[];
}) {
  if (settlements.length === 0) {
    return <p className="px-4 pb-4 text-xs text-muted-foreground">No claims settled in this period.</p>;
  }

  const working = settlements.filter((s) => s.currency === WORKING_CURRENCY);
  const others = settlements.filter((s) => s.currency !== WORKING_CURRENCY);

  const series: Series[] = [
    {
      label: 'Benefits paid',
      tone: 'ink',
      points: working
        .filter((s): s is SettlementMonth & { totalApproved: NonNullable<SettlementMonth['totalApproved']> } =>
          s.totalApproved !== null,
        )
        .map((s) => ({
          month: s.month,
          value: Number(s.totalApproved.amount),
          display: formatMoney(s.totalApproved),
        })),
    },
  ];

  const latest = working.at(-1);
  const description =
    `Benefits paid by month, in ${WORKING_CURRENCY}.` +
    (latest?.totalApproved
      ? ` ${monthLabel(latest.month)}: ${formatMoney(latest.totalApproved)} across ${latest.claims} claims.`
      : '');

  return (
    <>
      <BarChart series={series} months={months} description={description} />
      {others.length > 0 && (
        // The currencies are NAMED, not totalled: adding their claim counts here would be the browser adding, which
        // it may not do even for counts. Each month's own figure is on the card above.
        <p className="px-4 pb-2 text-xs text-muted-foreground">
          There is also paid in {[...new Set(others.map((s) => s.currency))].join(', ')}, not drawn here and not
          added in.
        </p>
      )}
    </>
  );
}
