import type { ReportChart } from '@/api/reporting';
import { Legend } from '@/components/charts/ChartTip';
import { TrendChart, type TrendSeries } from '@/components/charts/TrendChart';
import type { ChartColor } from '@/components/charts/chartScale';
import { formatCell } from './reportParams';

const PALETTE: ChartColor[] = ['chart-1', 'chart-2', 'chart-3', 'chart-4', 'chart-5'];

/**
 * A report's TREND chart (2026-11-09, the solvency forecast): what happened as solid lines, the projection dashed. A
 * dashed series continues the line before it, so it takes that line's colour. Every value is the server's; the browser
 * only places the points.
 */
export function ReportTrend({ chart }: { chart: ReportChart }) {
  const colors: ChartColor[] = [];
  let next = 0;
  chart.series.forEach((s, i) => {
    colors.push(s.dashed && i > 0 ? colors[i - 1]! : PALETTE[next++ % PALETTE.length]!);
  });
  const periods = chart.series[0]?.points.map((p) => p.period) ?? [];
  const series: TrendSeries[] = chart.series.map((s, i) => ({
    label: s.label,
    color: colors[i]!,
    ...(s.dashed ? { dashed: true } : {}),
    points: s.points.map((p) => ({
      key: p.period,
      value: p.value == null || p.value === '' ? null : Number(p.value),
      display: p.value == null || p.value === '' ? '' : formatCell(chart.unit, p.value),
    })),
  }));
  return (
    <div className="space-y-3">
      <Legend items={chart.series.map((s, i) => ({ label: s.label, color: colors[i]!, ...(s.dashed ? { dashed: true } : { line: true }) }))} />
      <TrendChart
        categories={periods.map((p) => ({ key: p, tick: p, long: p }))}
        series={series}
        description={chart.description}
      />
    </div>
  );
}
