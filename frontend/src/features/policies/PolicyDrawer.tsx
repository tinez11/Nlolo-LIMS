import { ArrowRight } from 'lucide-react';
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { PartyName } from '@/components/PartyName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Sheet, SheetBody, SheetContent, SheetFooter, SheetHeader } from '@/components/ui/sheet';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectDetail, usePolicyStore } from '@/store/policyStore';
import { Field } from '@/components/Field';

/**
 * The preview half of drawer-previews-page-acts.
 *
 * Read-only by design. It is dismissable by clicking the backdrop, so it must never
 * host a state transition -- endorsements, surrender and beneficiary edits all live
 * on the full page where the action cannot be lost to a stray click.
 */
export function PolicyDrawer({
  policyNumber,
  onClose,
}: {
  policyNumber: string | null;
  onClose: () => void;
}) {
  const detail = usePolicyStore(selectDetail(policyNumber ?? ''));
  const loadDetail = usePolicyStore((s) => s.loadDetail);

  useEffect(() => {
    if (policyNumber) void loadDetail(policyNumber);
  }, [policyNumber, loadDetail]);

  const policy = detail.data;

  return (
    <Sheet
      open={policyNumber !== null}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <SheetContent aria-label="Policy preview">
        <SheetHeader
          title={policyNumber ?? 'Policy'}
          subtitle={policy?.status ? undefined : 'Loading details'}
          action={policy?.status ? <StatusBadge kind="policy" value={policy.status} /> : undefined}
        />

        <SheetBody>
          {/* isInitialLoad, not a 'loading'-only check: the fetch fires from an
              effect that runs AFTER first render, so there is a real frame at
              status 'idle' that a 'loading'-only check would fall through, and the
              drawer would flash empty content for one paint before the request had
              even started. */}
          {isInitialLoad(detail) && <LoadingBlock />}

          {detail.status === 'error' && detail.error && detail.data === null && (
            <ErrorPanel
              error={detail.error}
              {...(policyNumber ? { onRetry: () => void loadDetail(policyNumber) } : {})}
            />
          )}

          {policy && (
            <dl className="space-y-0">
              <Field label="Sum assured" value={formatMoney(policy.sumAssured)} emphasis />
              <Field
                label="Premium"
                value={
                  <>
                    {formatMoney(policy.premium)}
                    {policy.premiumFrequency && (
                      <span className="ml-1 text-xs text-subtle-foreground">
                        {policy.premiumFrequency.toLowerCase()}
                      </span>
                    )}
                  </>
                }
              />
              <Field
                label="Cash value"
                value={formatMoney(policy.cashValue)}
                // Platform-wide gap, not a display bug: PolicyAccount.cashValueAmount
                // is set to zero at issuance and no production path ever credits it,
                // so this reads 0.00 for every policy. Saying so beats letting a
                // finance officer read it as a real figure.
                note="Always 0.00 until the platform credits cash value"
              />
              <Field label="Issued" value={formatDate(policy.issueDate)} />
              {/* The drawer is a preview, so only maturity earns a row here -- it is the
                  one term fact someone scanning a list actually asks for. Commencement
                  and the paying term are on the full page. */}
              {policy.maturityDate && (
                <Field label="Matures" value={formatDate(policy.maturityDate)} />
              )}
              <Field
                label="Policyholder"
                value={
                  policy.policyholderPartyId ? (
                    <PartyName partyId={policy.policyholderPartyId} />
                  ) : (
                    '—'
                  )
                }
              />
              <Field
                label="Agent of record"
                value={
                  policy.agentOfRecordId ? (
                    <span className="font-mono text-xs">{policy.agentOfRecordId}</span>
                  ) : (
                    'Direct — no agent'
                  )
                }
              />
              <Field
                label="Beneficiaries"
                value={
                  policy.beneficiaries && policy.beneficiaries.length > 0
                    ? `${policy.beneficiaries.length}`
                    : 'None recorded'
                }
              />
            </dl>
          )}
        </SheetBody>

        {policyNumber && (
          <SheetFooter>
            <Button asChild variant="outline" className="w-full justify-between">
              <Link to={`../policies/${encodeURIComponent(policyNumber)}`} relative="path">
                Full detail
                <ArrowRight />
              </Link>
            </Button>
          </SheetFooter>
        )}
      </SheetContent>
    </Sheet>
  );
}
