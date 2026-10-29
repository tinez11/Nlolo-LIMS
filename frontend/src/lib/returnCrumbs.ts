import { useSearchParams } from 'react-router-dom';

export interface Crumb {
  label: string;
  to: string;
}

/**
 * Lists that open records they do not own (2026-10-27): the approvals inbox and the payments register. A record
 * reached from one of them leads back to it, not to the list the record happens to belong to -- somebody working down
 * their approvals should land back in them, not in Payouts.
 */
const WORKING_LISTS: Record<string, Crumb> = {
  approvals: { label: 'Approvals', to: '/staff/approvals' },
  payments: { label: 'Payments', to: '/staff/payments' },
  'field-receipts': { label: 'Field receipts', to: '/staff/field-receipts' },
};

/** The link a working list uses to open a record, so the record can lead back. */
export function fromList(path: string, list: keyof typeof WORKING_LISTS): string {
  return `${path}${path.includes('?') ? '&' : '?'}from=${list}`;
}

/** The page's own breadcrumb, unless it was opened from a working list -- then that list. */
export function useReturnCrumbs(own: Crumb[]): Crumb[] {
  const [params] = useSearchParams();
  const from = params.get('from');
  const list = from ? WORKING_LISTS[from] : undefined;
  return list ? [list] : own;
}
