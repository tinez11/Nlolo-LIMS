import type { ReportFigure } from '@/api/reporting';
import { formatCell } from './reportParams';

/**
 * A figure written as its unit says (2026-11-09): money with two decimals, a count whole, a percentage with "%", days
 * with "days". Only presentation: the number is the server's, unchanged.
 */
export function formatFigure(value: string | null | undefined, unit: ReportFigure['unit']): string {
  if (value == null || value === '') return '—';
  if (unit === 'COUNT') return formatCell('COUNT', value);
  if (unit === 'PERCENT') return `${value}%`;
  if (unit === 'DAYS') return `${value} ${value === '1' || value === '1.0' ? 'day' : 'days'}`;
  return formatCell('MONEY', value);
}

/** A change of a percentage is in points, never a percentage of a percentage. */
function changeOf(f: ReportFigure, magnitude: string): string {
  if (f.unit === 'PERCENT') return `${magnitude} pts`;
  return formatFigure(magnitude, f.unit);
}

/** "up 72.40 (6.2%) from 1,168.10" -- every number the server's, put into words with its arrow. */
export function changeWords(f: ReportFigure): string | null {
  if (f.change == null || f.prior == null) return null;
  const down = f.change.startsWith('-');
  if (Number(f.change) === 0) return `no change from ${formatFigure(f.prior, f.unit)}`;
  const percent = f.changePercent ? ` (${f.changePercent.replace(/^-/, '')}%)` : '';
  return `${down ? 'down' : 'up'} ${changeOf(f, down ? f.change.slice(1) : f.change)}${percent} from ${formatFigure(f.prior, f.unit)}`;
}

/** Whether a change is good news for the insurer: a rise where rises are good, a fall where falls are. */
export function isGood(f: ReportFigure): boolean | null {
  if (f.change == null || f.goodWhen == null || Number(f.change) === 0) return null;
  const up = !f.change.startsWith('-');
  return up === (f.goodWhen === 'UP');
}
