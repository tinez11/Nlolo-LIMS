import { can } from '@/auth/access';

/**
 * Approving one's own work (2026-11-04, the user: a company with one underwriter or one finance person must still
 * finish its work). Each area has a permission that lets its holder sign what they prepared; nobody holds one unless
 * an administrator gives it, and the server records every such act by name.
 */
export const OWN_WORK = {
  underwriting: 'underwriting.decide-own',
  surrenders: 'policies.surrender-approve-own',
  creditLifeFiles: 'schemes.enrolments-own',
  claims: 'claims.settle-own',
  accounts: 'accounts.approve-own',
  receipts: 'collections.approve-own',
  payouts: 'payouts.approve-own',
  ledger: 'ledger.approve-own',
  ifrs17: 'ifrs17.approve-own',
  reinsurance: 'reinsurance.approve-own',
  funds: 'funds.approve-own',
  declarations: 'products.declare-approve-own',
  commission: 'commission.approve-own',
} as const;

export type OwnWorkArea = keyof typeof OWN_WORK;

/** Whether the signed-in person may sign their own work in this area. */
export function mayActAlone(area: OwnWorkArea): boolean {
  return can(OWN_WORK[area]);
}

/**
 * Whether the viewer is barred from signing this because it is their own: it is theirs, and they may not act alone
 * in this area. False for someone else's -- and false for no subject at all, which the server decides.
 */
export function barredAsOwn(viewer: string | null | undefined, preparer: string | null | undefined, area: OwnWorkArea) {
  return viewer != null && preparer != null && viewer === preparer && !mayActAlone(area);
}

/** What a gate says when the viewer signs their own work, as they may. */
export const SIGNING_OWN = 'This is your own; you may approve it alone, and it is recorded against your name.';
