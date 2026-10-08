/**
 * Plain words for the customer portal (2026-10-08, step 2): statuses, frequencies and product kinds as a policyholder
 * reads them, not as the platform stores them.
 */

const POLICY_STATUS: Record<string, string> = {
  PROPOSED: 'Awaiting first payment',
  ACTIVE: 'In force',
  REINSTATED: 'In force',
  PAID_UP: 'Paid up',
  SUSPENDED: 'Suspended',
  LAPSED: 'Lapsed',
  SURRENDERED: 'Surrendered',
  MATURED: 'Matured',
  EXPIRED: 'Ended',
  NOT_TAKEN_UP: 'Not taken up',
  CANCELLED_FREE_LOOK: 'Cancelled',
};

export function policyStatusText(status: string | null | undefined): string {
  return status ? POLICY_STATUS[status] ?? status.toLowerCase().replaceAll('_', ' ') : '—';
}

/** Whether the status is good news, a warning or neither -- for the badge colour. */
export function policyStatusTone(status: string | null | undefined): 'good' | 'warn' | 'plain' {
  if (status === 'ACTIVE' || status === 'REINSTATED' || status === 'PAID_UP') return 'good';
  if (status === 'PROPOSED' || status === 'SUSPENDED' || status === 'LAPSED') return 'warn';
  return 'plain';
}

export function perFrequency(frequency: string | null | undefined): string {
  switch (frequency) {
    case 'MONTHLY': return 'a month';
    case 'QUARTERLY': return 'a quarter';
    case 'ANNUALLY': return 'a year';
    case 'SINGLE': return 'once';
    default: return '';
  }
}

const CATEGORY: Record<string, string> = {
  TERM_LIFE: 'Term life', WHOLE_LIFE: 'Whole life', ENDOWMENT: 'Endowment', FUNERAL: 'Funeral cover',
  EDUCATION_SAVINGS: 'Education savings', ANNUITY: 'Annuity', UNIT_LINKED: 'Investment plan', CREDIT_LIFE: 'Credit life',
  GROUP_LIFE: 'Group life',
};

export function categoryText(category: string | null | undefined): string {
  return category ? CATEGORY[category] ?? category.toLowerCase().replaceAll('_', ' ') : '';
}

const CLAIM_STATUS: Record<string, string> = {
  REGISTERED: 'Claim received',
  UNDER_ASSESSMENT: 'Being reviewed',
  REOPENED: 'Being reviewed',
  APPROVED: 'Approved',
  SETTLEMENT_REQUESTED: 'Payment being processed',
  SETTLED: 'Paid',
  REJECTED: 'Declined',
};

export function claimStatusText(status: string): string {
  return CLAIM_STATUS[status] ?? status.toLowerCase().replaceAll('_', ' ');
}

const ROLE: Record<string, string> = {
  MAIN_MEMBER: 'Main member', SPOUSE: 'Spouse', CHILD: 'Child', PARENT: 'Parent', EXTENDED: 'Extended family',
};

export function roleText(role: string): string {
  return ROLE[role] ?? role;
}

const MONEY = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** "TZS 4,000,000.00"; an em dash for nothing. */
export function money(amount: number | string | null | undefined, currency: string | null | undefined): string {
  if (amount == null || amount === '') return '—';
  return `${currency ?? ''} ${MONEY.format(Number(amount))}`.trim();
}
