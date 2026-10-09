/**
 * The invoice status a payment-schedule line's WORDED status stands for, so the line wears the same badge
 * as the invoice it came from. The words are the server's own (`PolicyDocuments.status`), which splits DUE
 * into "Upcoming" and "Due"; both are an invoice that is DUE. A word this map does not know is passed through
 * unchanged, so the badge flags it as unrecognised instead of dressing it as a neutral.
 */
export function scheduleStatusLiteral(status: string): string {
  switch (status) {
    case 'Paid':
      return 'PAID';
    case 'Partly paid':
      return 'PARTIALLY_PAID';
    case 'Waived':
      return 'WAIVED';
    case 'Overdue':
      return 'OVERDUE';
    case 'In grace':
      return 'IN_GRACE';
    case 'Due':
    case 'Upcoming':
      return 'DUE';
    default:
      return status;
  }
}
