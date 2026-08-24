import { ArrowRight } from 'lucide-react';
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Sheet, SheetBody, SheetContent, SheetFooter, SheetHeader } from '@/components/ui/sheet';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectClaimDetail, useClaimStore } from '@/store/claimStore';
import { ClaimDetailsFields } from './ClaimDetailsFields';

/**
 * The preview half of drawer-previews-page-acts. Read-only by design -- it is
 * dismissable by clicking the backdrop, so it must never host a state transition
 * (assessment, settlement decision, reopen are not built in this slice at all,
 * and would belong on the full page if they were).
 */
export function ClaimDrawer({ claimId, onClose }: { claimId: string | null; onClose: () => void }) {
  const detail = useClaimStore(selectClaimDetail(claimId ?? ''));
  const loadDetail = useClaimStore((s) => s.loadDetail);

  useEffect(() => {
    if (claimId) void loadDetail(claimId);
  }, [claimId, loadDetail]);

  const claim = detail.data;

  return (
    <Sheet
      open={claimId !== null}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <SheetContent aria-label="Claim preview">
        <SheetHeader
          title={claim?.claimType ?? 'Claim'}
          subtitle={claim?.status ? undefined : 'Loading details'}
          action={claim?.status ? <StatusBadge kind="claim" value={claim.status} /> : undefined}
        />

        <SheetBody>
          {/* isInitialLoad, not a 'loading'-only check -- see PolicyDrawer for why:
              the fetch fires from an effect that runs after first render, so there
              is a real frame at status 'idle' a loading-only check would miss. */}
          {isInitialLoad(detail) && <LoadingBlock />}

          {detail.status === 'error' && detail.error && detail.data === null && (
            <ErrorPanel
              error={detail.error}
              {...(claimId ? { onRetry: () => void loadDetail(claimId) } : {})}
            />
          )}

          {claim && (
            <dl className="space-y-0">
              <Field
                label="Policy"
                value={<span className="font-mono text-xs">{claim.policyNumber}</span>}
              />
              <Field label="Date of event" value={formatDate(claim.dateOfEvent)} />
              {claim.approvedAmount && (
                <Field label="Approved amount" value={formatMoney(claim.approvedAmount)} emphasis />
              )}
              {claim.requiresContestabilityReview && (
                <Field
                  label="Contestability"
                  value="Requires review"
                  note="Falls inside the policy's contestability window"
                />
              )}
              <ClaimDetailsFields details={claim.details} />
              <Field
                label="Claimant"
                value={<span className="font-mono text-xs">{claim.claimantPartyId}</span>}
                note="No party lookup endpoint exists yet"
              />
            </dl>
          )}
        </SheetBody>

        {claimId && (
          <SheetFooter>
            <Button asChild variant="outline" className="w-full justify-between">
              <Link to={`../claims/${encodeURIComponent(claimId)}`} relative="path">
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
