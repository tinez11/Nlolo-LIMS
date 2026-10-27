import type { MonthEndStep } from '@/api/types';

/**
 * Where each month-end step is done (2026-10-27). The timeline only reads; every act stays on the page that already
 * does it, opened on the same month.
 */
export function stepLink(step: MonthEndStep['key'], period: string): { to: string; label: string } {
  const month = encodeURIComponent(period);
  switch (step) {
    case 'queue':
      return { to: '/staff/unposted-events', label: 'Unposted events' };
    case 'journals':
      return { to: '/staff/manual-journals', label: 'Manual journals' };
    case 'closing':
    case 'lock':
      return { to: '/staff/periods', label: 'Accounting periods' };
    case 'allocation':
    case 'extract':
    case 'results':
    case 'reconcile':
      return { to: `/staff/ifrs17-engine?period=${month}`, label: 'IFRS 17 engine' };
  }
}

/** The words a step's state reads as, beside its badge. */
export const STATE_WORDS: Record<MonthEndStep['state'], string> = {
  DONE: 'Done',
  WAITING: 'Waiting on someone',
  TODO: 'To do',
  BLOCKED: 'Stops the lock',
};

/** The month before or after a YYYY-MM month. */
export function shiftMonth(period: string, by: number): string {
  const [y, m] = period.split('-').map(Number);
  const d = new Date(Date.UTC(y!, m! - 1 + by, 1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}

/** "October 2026" for 2026-10. */
export function monthName(period: string): string {
  const [y, m] = period.split('-').map(Number);
  return new Intl.DateTimeFormat('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' })
    .format(new Date(Date.UTC(y!, m! - 1, 1)));
}
