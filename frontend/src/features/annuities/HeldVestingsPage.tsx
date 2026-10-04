import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { formatDate, formatInstant } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useAnnuityStore } from '@/store/annuityStore';

/**
 * Every pension the vesting sweep could not vest (product step 5 D2), oldest hold first: a reported
 * death, a changed date of birth or sex, a choice the current version cannot price, or a vesting that
 * failed and rolled back. Each is retried daily; each row opens the policy's Annuity tab, where the
 * hold is cleared by a new instruction or a re-confirmation of age.
 */
export function HeldVestingsPage() {
  const held = useAnnuityStore((s) => s.held);
  const loadHeld = useAnnuityStore((s) => s.loadHeld);

  useEffect(() => {
    void loadHeld();
  }, [loadHeld]);

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Policies', to: '/staff/policies' }]}
        title="Held vestings"
        description="Pensions due to vest that the daily sweep could not vest, and why."
      />
      <div className="px-6 pb-8">
        {isInitialLoad(held) ? (
          <LoadingBlock />
        ) : held.status === 'error' && held.error && held.data === null ? (
          <ErrorPanel error={held.error} onRetry={() => void loadHeld()} />
        ) : (held.data ?? []).length === 0 ? (
          <EmptyState title="No held vesting" description="Every pension due to vest has vested." />
        ) : (
          <div className="overflow-x-auto rounded-md border border-border">
            <table className="w-full text-sm" aria-label="Held vestings">
              <thead className="text-left text-xs text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 font-medium">Policy</th>
                  <th className="px-3 py-2 font-medium">Vesting date</th>
                  <th className="px-3 py-2 font-medium">Hold reason</th>
                  <th className="px-3 py-2 font-medium">Held since</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {(held.data ?? []).map((v) => (
                  <tr key={v.policyNumber}>
                    <td className="px-3 py-2">
                      <Link className="font-mono text-xs underline" to={`/staff/policies/${encodeURIComponent(v.policyNumber)}`}>
                        {v.policyNumber}
                      </Link>
                    </td>
                    <td className="px-3 py-2">{formatDate(v.vestingDate)}</td>
                    <td className="px-3 py-2">{v.holdReason}</td>
                    <td className="px-3 py-2">{formatInstant(v.heldAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  );
}
