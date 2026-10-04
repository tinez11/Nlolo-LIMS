import type { EntryType } from '@/api/types';

/**
 * Each entry type in a clerk's words -- the same words the PDF statement uses (StatementLabels on
 * the server), so the screen and the paper never describe one line two ways.
 */
export const ENTRY_LABEL: Record<EntryType, string> = {
  CONTRIBUTION: 'Premium',
  TOP_UP: 'Top-up',
  TRANSFER_IN: 'Transfer in',
  ALLOCATION_CHARGE: 'Allocation charge',
  POLICY_FEE: 'Policy fee',
  INTEREST: 'Interest',
  WITHDRAWAL: 'Withdrawal',
  SURRENDER: 'Surrender',
  MATURITY: 'Maturity',
  DEATH_CLAIM: 'Death claim',
  FREE_LOOK_REFUND: 'Free-look cancellation',
  ADJUSTMENT: 'Adjustment',
  REVERSAL: 'Reversal',
  VESTING: 'Pension vested',
};
