import { useEffect, useState } from 'react';
import { listEnrolmentSubmissions } from '@/api/creditLife';
import { getSchemeMember, listPolicyCredits } from '@/api/policies';
import type { EnrolmentSubmissionView, PolicyMemberView, PremiumCreditView } from '@/api/types';

/** A credit, with the member it was given back to. */
export interface CreditLine {
  credit: PremiumCreditView;
  member: PolicyMemberView | null;
}

export interface InvoiceReconciliation {
  /** Credits keyed by the invoice they are credited against. */
  creditsByInvoice: Record<string, CreditLine[]>;
  /** The monthly files, keyed by submission id -- what raised each credit-life invoice. */
  filesById: Record<string, EnrolmentSubmissionView>;
}

/**
 * What the invoices panel needs to reconcile each invoice against the monthly files: the credits
 * given back off it, with WHO each was for, and the file that charged it.
 *
 * Credits and files are read for every policy; on individual business both are simply empty
 * (the enrolment read is a credit-life endpoint, so its failure is swallowed as "no files").
 * A failed member lookup leaves the credit on screen without a name rather than dropping it.
 */
export function useInvoiceReconciliation(policyNumber: string, isScheme: boolean): InvoiceReconciliation {
  const [state, setState] = useState<InvoiceReconciliation>({ creditsByInvoice: {}, filesById: {} });

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const [credits, files] = await Promise.all([
        listPolicyCredits(policyNumber).catch(() => [] as PremiumCreditView[]),
        isScheme
          ? listEnrolmentSubmissions(policyNumber).catch(() => [] as EnrolmentSubmissionView[])
          : Promise.resolve([] as EnrolmentSubmissionView[]),
      ]);
      const lines = await Promise.all(
        credits.map(async (credit) => ({
          credit,
          member: await getSchemeMember(policyNumber, credit.policyMemberId).catch(() => null),
        })),
      );
      if (cancelled) return;
      const creditsByInvoice: Record<string, CreditLine[]> = {};
      for (const line of lines) {
        (creditsByInvoice[line.credit.originalInvoiceId] ??= []).push(line);
      }
      const filesById: Record<string, EnrolmentSubmissionView> = {};
      for (const file of files) {
        if (file.submissionId) filesById[file.submissionId] = file;
      }
      setState({ creditsByInvoice, filesById });
    })();
    return () => {
      cancelled = true;
    };
  }, [policyNumber, isScheme]);

  return state;
}
