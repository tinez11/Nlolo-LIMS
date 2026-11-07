import { useEffect, useState } from 'react';
import { getLoanRepaidFromPayout, type LoanRepaidPayout } from '@/api/policies';
import type { Money } from '@/api/types';

/**
 * What a payout repaid of the policy's loans (2026-11-07, decision E7), read from the server; null while unknown or
 * when it repaid nothing.
 */
export function useLoanRepaid(payout: LoanRepaidPayout, payoutRef: string | null | undefined): Money | null {
  const [repaid, setRepaid] = useState<Money | null>(null);
  useEffect(() => {
    if (!payoutRef) return;
    let cancelled = false;
    getLoanRepaidFromPayout(payout, payoutRef)
      .then((r) => {
        // A comparison with zero, not arithmetic: the line shows only when the payout repaid something.
        if (!cancelled) setRepaid(Number(r.repaid.amount) > 0 ? r.repaid : null);
      })
      .catch(() => {
        if (!cancelled) setRepaid(null);
      });
    return () => {
      cancelled = true;
    };
  }, [payout, payoutRef]);
  return repaid;
}
