import type { ReportFigure } from '@/api/reporting';
import { formatCell } from './reportParams';

/** "up 72.40 (6.2%) from 1,168.10" -- every number the server's, put into words with its arrow. */
export function changeWords(f: ReportFigure): string | null {
  if (f.change == null || f.prior == null) return null;
  const down = f.change.startsWith('-');
  const amount = formatCell('MONEY', down ? f.change.slice(1) : f.change);
  const percent = f.changePercent ? ` (${f.changePercent.replace(/^-/, '')}%)` : '';
  if (Number(f.change) === 0) return `no change from ${formatCell('MONEY', f.prior)}`;
  return `${down ? 'down' : 'up'} ${amount}${percent} from ${formatCell('MONEY', f.prior)}`;
}

/** Whether a change is good news for the insurer: a rise where rises are good, a fall where falls are. */
export function isGood(f: ReportFigure): boolean | null {
  if (f.change == null || f.goodWhen == null || Number(f.change) === 0) return null;
  const up = !f.change.startsWith('-');
  return up === (f.goodWhen === 'UP');
}

