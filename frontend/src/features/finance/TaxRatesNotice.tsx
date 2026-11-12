import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { can } from '@/auth/access';
import { useLedgerControlsStore } from '@/store/ledgerControlsStore';

const WHAT: Record<string, string> = {
  COMMISSION_WITHHOLDING_RATE: 'Commission withholding tax is not set: commission is paid in full, with nothing withheld.',
  PREMIUM_LEVY_RATE: 'Premium levy is not set: no levy is booked on premiums.',
};

/**
 * A rate the posting rules need but nobody has set (2026-11-12, finance finding 101: on a new company commission was
 * paid with no withholding tax and no levy booked, and nothing said so). Shown where it bites -- month-end, a
 * commission payout -- with the way to set it. Says nothing when every rate is set, or to someone who cannot see the
 * ledger.
 *
 * @param only the one rate this place is about; every rate when absent
 */
export function TaxRatesNotice({ only }: { only?: keyof typeof WHAT }) {
  const rules = useLedgerControlsStore((s) => s.rules);
  const loadRules = useLedgerControlsStore((s) => s.loadRules);
  const mayRead = can('ledger.view');
  useEffect(() => {
    if (mayRead) void loadRules();
  }, [mayRead, loadRules]);

  const unset = (rules.data?.settings ?? []).filter((s) => s.value == null && WHAT[s.key] && (!only || s.key === only));
  if (!mayRead || unset.length === 0) return null;
  return (
    <div role="status" className="space-y-1 rounded-md bg-status-warning-bg px-3 py-2 text-sm text-status-warning-fg">
      {unset.map((s) => <p key={s.key}>{WHAT[s.key]}</p>)}
      <Link to="/staff/accounting-policies" className="text-xs font-medium underline underline-offset-2">
        Set it in Accounting policies
      </Link>
    </div>
  );
}
