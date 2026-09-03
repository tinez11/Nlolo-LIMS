import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { type FieldErrors, useForm } from 'react-hook-form';
import { Button } from '@/components/ui/button';
import { ConfirmAct } from '@/components/ConfirmAct';
import { FormField } from '@/components/FormField';
import { Receipt } from '@/components/Receipt';
import { formatMoney } from '@/lib/money';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { selectDecidingSettlement, useClaimStore } from '@/store/claimStore';
import {
  blankApproveDecision,
  blankRejectDecision,
  settlementDecisionFormSchema,
  toApiRequest,
  type SettlementDecisionFormValues,
} from './settlementDecisionForm';
import { Input, Textarea } from '@/components/ui/input';

/**
 * `POST /claims/{claimId}/settlement-decision` -- `CLAIMS_MANAGER` role only,
 * rendered by the parent only for a claim currently UNDER_ASSESSMENT (or
 * REGISTERED for a MATURITY claim, which auto-approves with no assessment at
 * all). `ClaimsApiImpl` additionally rejects the same PERSON who assessed this
 * claim from also deciding it -- a 422 this panel surfaces plainly rather than
 * trying to pre-empt, since the frontend has no way to know who assessed a
 * claim (no assessment-history endpoint exists to check against).
 *
 * Approve and reject are modelled as a genuine `z.discriminatedUnion`, not a
 * single schema with optional fields -- `reset()`, not `setValue()`, switches
 * branches, matching RegisterClaimPage's own claim-type switcher: the two
 * branches share no fields, so there is nothing to preserve across a toggle.
 */
export function ClaimSettlementPanel({ claimId }: { claimId: string }) {
  const decideSettlement = useClaimStore((s) => s.decideSettlement);
  const resetDecideSettlement = useClaimStore((s) => s.resetDecideSettlement);
  const deciding = useClaimStore(selectDecidingSettlement(claimId));
  // Minted once for the lifetime of this panel, reused across retries -- same
  // idiom as RegisterClaimPage, even though this key is only load-bearing on
  // approval (see api/claims.ts's decideSettlement doc).
  const [attempt] = useState<MutationAttempt>(() => startMutation());

  useEffect(() => {
    resetDecideSettlement(claimId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [claimId]);

  const {
    register,
    handleSubmit,
    watch,
    reset,
    formState: { errors },
  } = useForm<SettlementDecisionFormValues>({
    resolver: zodResolver(settlementDecisionFormSchema),
    defaultValues: blankApproveDecision(),
  });

  // eslint-disable-next-line react-hooks/incompatible-library -- see RegisterClaimPage
  const approved = watch('approved');

  /**
   * Validation runs FIRST, then the confirmation.
   *
   * `handleSubmit` parks the validated values here rather than sending them, so
   * the second click has something true to describe -- confirming an amount and
   * then being told it is malformed would make the confirmation a formality to
   * click past, which is the failure mode this whole step exists to avoid.
   */
  const [pending, setPending] = useState<SettlementDecisionFormValues | null>(null);

  async function commit(values: SettlementDecisionFormValues) {
    await decideSettlement(claimId, toApiRequest(values), attempt);
    if (useClaimStore.getState().decidingSettlement[claimId]?.status === 'success') {
      setPending(null);
    }
  }

  const decided = deciding.status === 'success' ? deciding.data : null;
  if (decided) {
    return (
      <div className="p-4">
        <Receipt
          heading={decided.status === 'REJECTED' ? 'Claim rejected' : 'Settlement approved'}
          /*
            Only the amount, and only when there is one.

            The outcome is already the heading, and the claim's status badge and
            id are both rendered elsewhere on this page -- repeating them here
            would put the same string on screen twice, which is how a spec's
            `getByText('Rejected')` becomes ambiguous and how a reader stops
            being able to tell which one is authoritative. A receipt reports what
            CHANGED, not a second copy of the record.
          */
          lines={
            decided.approvedAmount
              ? [{ label: 'Approved', value: formatMoney(decided.approvedAmount) }]
              : []
          }
          // Every field above comes off the ClaimView the server returned. The
          // note is the one thing the console knows and the response does not
          // say -- and it is the consequence an operator most needs on the
          // record, because nothing on this screen would otherwise reveal it.
          note={
            decided.status === 'REJECTED'
              ? 'A claims manager can reopen a rejected claim if new evidence arrives.'
              : 'Disbursement settles asynchronously through the payment rail. The policy is now closed and reopening this claim will not reverse that.'
          }
        />
      </div>
    );
  }

  return (
    <form className="space-y-3 p-4" onSubmit={(e) => void handleSubmit(setPending)(e)}>
      <div className="flex items-center gap-1.5">
        <Button
          type="button"
          size="sm"
          variant={approved ? 'primary' : 'outline'}
          onClick={() => reset(blankApproveDecision())}
        >
          Approve
        </Button>
        <Button
          type="button"
          size="sm"
          variant={!approved ? 'primary' : 'outline'}
          onClick={() => reset(blankRejectDecision())}
        >
          Reject
        </Button>
      </div>

      {approved ? (
        <>
          <div className="grid grid-cols-[1fr_auto] gap-2">
            <FormField label="Approved amount" error={fieldError(errors, 'approvedAmount')}>
              <Input
                placeholder="1500000.00"
                {...register('approvedAmount')}
              />
            </FormField>
            <FormField label="Currency" error={fieldError(errors, 'approvedCurrency')}>
              <Input
                className="w-20 uppercase"
                {...register('approvedCurrency')}
              />
            </FormField>
          </div>
          <FormField label="Payee reference" error={fieldError(errors, 'payeeRef')}>
            <Input
              placeholder="Mobile-money destination"
              {...register('payeeRef')}
            />
          </FormField>
        </>
      ) : (
        <FormField label="Rejection reason (optional)">
          <Textarea
            className="min-h-16"
            placeholder="Insufficient evidence"
            {...register('rejectionReason')}
          />
        </FormField>
      )}

      {deciding.status === 'error' && deciding.error && (
        <div role="alert" className="rounded-md bg-status-danger-bg px-3 py-2 text-xs text-status-danger-fg">
          {deciding.error.detail ?? deciding.error.title}
          {deciding.error.traceId && (
            <span className="ml-2 font-mono text-[10px] opacity-80">({deciding.error.traceId})</span>
          )}
        </div>
      )}

      {pending ? (
        <ConfirmAct
          heading={pending.approved ? 'Approve this settlement?' : 'Reject this claim?'}
          tone="danger"
          consequence={
            pending.approved ? (
              <>
                Pay{' '}
                <strong>
                  {pending.approvedCurrency.toUpperCase()} {pending.approvedAmount}
                </strong>{' '}
                to <strong>{pending.payeeRef}</strong>.
              </>
            ) : (
              <>
                Refuse this claim
                {pending.rejectionReason?.trim() ? (
                  <>
                    {' '}
                    on the grounds: <strong>{pending.rejectionReason.trim()}</strong>
                  </>
                ) : (
                  <> with no reason recorded</>
                )}
                .
              </>
            )
          }
          /*
            Both lines are facts about the backend, checked in Claim.java rather
            than assumed. Approving is the one an operator cannot discover from
            this screen: `claims.application.PaymentEventListener` closes the
            policy on settlement, and `Policy` has no transition out of MATURED
            or SURRENDERED -- so a claim reopened from SETTLED, even if it is
            then re-rejected, leaves the policy permanently closed. That is
            documented in Claim.reopen()'s own Javadoc as a known asymmetry.
            Rejection really is recoverable, and says so, which is what keeps
            the warning above meaningful.
          */
          reversal={
            pending.approved
              ? 'Money moves, and the policy closes permanently — reopening this claim later will not reverse the closure or restart billing.'
              : 'A claims manager can reopen a rejected claim, but this decision stays on the record.'
          }
          // Never the arming button's own words: "Reject claim" twice, a click
          // apart, is how the second click becomes as automatic as the first.
          confirmLabel={pending.approved ? 'Approve and pay' : 'Record the rejection'}
          busy={deciding.status === 'loading'}
          onConfirm={() => void commit(pending)}
          onCancel={() => setPending(null)}
        />
      ) : (
        <Button type="submit" size="sm" variant="primary" disabled={deciding.status === 'loading'}>
          {approved ? 'Approve claim' : 'Reject claim'}
        </Button>
      )}
    </form>
  );
}

// react-hook-form's FieldErrors does not narrow per-branch on a discriminated
// union used directly as TFieldValues, the same limitation claimRegisterForm's
// nested `details` union hits -- see RegisterClaimPage's detailError for why
// this reads correctly at runtime regardless.
function fieldError(errors: FieldErrors<SettlementDecisionFormValues>, field: string): string | undefined {
  const rec = errors as Record<string, { message?: string } | undefined>;
  return rec[field]?.message;
}
