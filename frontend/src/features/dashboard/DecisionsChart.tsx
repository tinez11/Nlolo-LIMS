import type { DecisionSeriesPoint } from '@/api/dashboard';
import { BarChart, type Series } from '@/components/charts/BarChart';
import { countValue } from '@/lib/counts';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "2026-09" -> "Sep 2026". */
const monthLabel = (month: string) => `${MONTHS[Number(month.slice(5, 7)) - 1] ?? month} ${month.slice(0, 4)}`;

/**
 * Underwriting decisions by month: what was taken on, against what was refused (2026-10-16).
 *
 * <p><b>No arithmetic here.</b> Each point arrives already grouped -- `accepted` is ACCEPT plus LOADED, added on
 * the server, because the browser may not add even counts. This maps two numbers onto two series and nothing else.
 *
 * <p>POSTPONED is in neither series: it closed no case, and a third bar a month would cost the chart its
 * readability for a figure the card beside it already shows.
 */
export function DecisionsChart({ series, months }: { series: DecisionSeriesPoint[]; months: string[] }) {
  if (series.length === 0) {
    return <p className="px-4 pb-4 text-xs text-muted-foreground">No decisions in this period.</p>;
  }

  const bars: Series[] = [
    {
      label: 'Accepted',
      tone: 'ink',
      points: series.map((p) => ({
        month: p.month,
        value: p.accepted,
        display: `${countValue(p.accepted)} ${p.accepted === 1 ? 'case' : 'cases'}`,
      })),
    },
    {
      label: 'Declined',
      tone: 'muted',
      points: series.map((p) => ({
        month: p.month,
        value: p.declined,
        display: `${countValue(p.declined)} ${p.declined === 1 ? 'case' : 'cases'}`,
      })),
    },
  ];

  const latest = series[series.length - 1];
  const description =
    'Underwriting decisions by month, accepted against declined. Postponements are not shown.' +
    (latest
      ? ` ${monthLabel(latest.month)}: ${latest.accepted} accepted, ${latest.declined} declined.`
      : '');

  return <BarChart series={bars} months={months} description={description} />;
}
