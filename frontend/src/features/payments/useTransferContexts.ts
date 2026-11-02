import { useEffect, useState } from 'react';
import { getClaim } from '@/api/claims';
import { getPolicy, getSchemeMember } from '@/api/policies';
import type { AwaitingEftView, ClaimView, InvoiceView, PolicyMemberView, PolicyView } from '@/api/types';
import { borrowerFileInvoice } from './borrowerFile';

/** Everything about the claim behind one transfer that the queue needs to say whose it is. */
export interface TransferContext {
  claim: ClaimView | null;
  policy: PolicyView | null;
  member: PolicyMemberView | null;
  /** Credit life: the invoice for the file the borrower joined on, which must be paid first (2026-11-02). */
  fileInvoice: InvoiceView | null;
  /** True once every lookup has answered, successfully or not. */
  loaded: boolean;
}

/**
 * The claim, its policy and -- on a scheme -- the member, for every claim-settlement row.
 *
 * Loaded here rather than by payment's backend, because payment may depend on reference data
 * only and knows none of these. Keyed by disbursement id, so a row whose lookups fail simply
 * renders what it has; one broken claim does not blank the queue.
 */
export function useTransferContexts(rows: AwaitingEftView[]): Record<string, TransferContext> {
  const [contexts, setContexts] = useState<Record<string, TransferContext>>({});
  const key = rows.map((r) => r.disbursementId).join(',');

  useEffect(() => {
    let cancelled = false;
    for (const row of rows) {
      if (row.purpose !== 'CLAIM_SETTLEMENT') continue;
      void (async () => {
        const claim = await getClaim(row.sourceRef).catch(() => null);
        const [policy, member] = await Promise.all([
          claim ? getPolicy(claim.policyNumber).catch(() => null) : Promise.resolve(null),
          claim?.policyMemberId
            ? getSchemeMember(claim.policyNumber, claim.policyMemberId).catch(() => null)
            : Promise.resolve(null),
        ]);
        const fileInvoice = claim && policy?.productCategory === 'CREDIT_LIFE'
          ? await borrowerFileInvoice(claim.policyNumber, member)
          : null;
        if (!cancelled) {
          setContexts((current) => ({
            ...current,
            [row.disbursementId]: { claim, policy, member, fileInvoice, loaded: true },
          }));
        }
      })();
    }
    return () => {
      cancelled = true;
    };
    // Keyed on the SET of rows, not the array identity: a refresh that returns the same queue
    // must not refetch every claim behind it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return contexts;
}
