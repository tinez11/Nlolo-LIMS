/**
 * How a schedule line's status reads on screen (2026-10-07): settled lines quiet, money owed now in the danger
 * colour, the rest plain. The words themselves are the server's.
 */
export function scheduleTone(status: string): string {
  switch (status) {
    case 'Paid':
    case 'Waived':
      return 'text-muted-foreground';
    case 'Overdue':
    case 'Due':
    case 'In grace':
    case 'Partly paid':
      return 'font-medium text-status-danger-fg';
    default:
      return '';
  }
}
