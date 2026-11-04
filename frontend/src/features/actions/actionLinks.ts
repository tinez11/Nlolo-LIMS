import type { ActionItem } from '@/api/actions';

const id = (item: ActionItem) => encodeURIComponent(item.reference);

interface Kind {
  /** The group's heading, in the words of the person doing it: "Proposals to review". */
  label: string;
  /** Where one job is done, relative to the realm. */
  item: (item: ActionItem) => string;
  /** The full list of this kind, relative to the realm, where one exists. */
  all?: string;
}

/**
 * Each kind of job (2026-11-03): what it is called, where one is done -- the page that already does it, so the bell
 * never grows a second way to act -- and where the whole list lives.
 */
const KINDS: Record<string, Kind> = {
  'proposal-returned': { label: 'Proposals returned to you', item: (i) => `proposals/${id(i)}`, all: 'proposals?view=needs-action' },
  'proposal-postponed': { label: 'Proposals postponed', item: (i) => `proposals/${id(i)}`, all: 'proposals?view=needs-action' },
  'kyc-resend': { label: 'Documents rejected', item: (i) => `parties/${id(i)}?tab=kyc`, all: 'clients?kycStatus=REJECTED' },
  'offer-closing': { label: 'Offers closing unpaid', item: (i) => `policies/${id(i)}?tab=billing`, all: 'policies?status=PROPOSED' },
  'proposal-review': { label: 'Proposals to review', item: (i) => `underwriting/${id(i)}`, all: 'underwriting?status=OPEN' },
  'proposal-referred': { label: 'Sent to a senior', item: (i) => `underwriting/${id(i)}` },
  'issue-failed': { label: 'Policies not created', item: (i) => `underwriting/${id(i)}` },
  'kyc-verify': { label: 'Documents to verify', item: (i) => `parties/${id(i)}?tab=kyc`, all: 'clients/individuals?kycStatus=PENDING' },
  'claim-assess': { label: 'Claims to assess', item: (i) => `claims/${id(i)}`, all: 'claims?status=REGISTERED' },
  'claim-settle': { label: 'Claims to decide', item: (i) => `claims/${id(i)}`, all: 'claims?status=UNDER_ASSESSMENT' },
  'credit-life-file': {
    label: 'Credit-life files to accept',
    item: (i) => `credit-life-schemes/${encodeURIComponent(i.policyNumber ?? '')}`,
  },
  signatures: { label: 'Waiting for your signature', item: () => 'approvals', all: 'approvals' },
};

/** A kind this console does not know yet keeps its server name rather than vanishing. */
export function kindLabel(kind: string): string {
  return KINDS[kind]?.label ?? kind;
}

/** Null for a kind this console does not know yet: listed, unlinked, rather than dropped. */
export function actionLink(realm: 'staff' | 'agents', kind: string, item: ActionItem): string | null {
  const to = KINDS[kind]?.item(item);
  return to ? `/${realm}/${to}` : null;
}

/** The full list of a kind, or null where the bell is its only list. */
export function allLink(realm: 'staff' | 'agents', kind: string): string | null {
  const to = KINDS[kind]?.all;
  return to ? `/${realm}/${to}` : null;
}

/** "5 min ago", "3 h ago", "2 days ago" -- how long a job has waited, from the server's instant. */
export function waitedFor(since: string | null | undefined, now = Date.now()): string {
  if (!since) return '';
  const minutes = Math.max(0, Math.round((now - new Date(since).getTime()) / 60_000));
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.round(hours / 24);
  return days === 1 ? 'yesterday' : `${days} days ago`;
}
