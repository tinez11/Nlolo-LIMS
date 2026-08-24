import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { type FieldErrors, useForm } from 'react-hook-form';
import { Button } from '@/components/ui/button';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { selectDecidingSettlement, useClaimStore } from '@/store/claimStore';
import {
  blankApproveDecision,
  blankRejectDecision,
  settlementDecisionFormSchema,
  toApiRequest,
  type SettlementDecisionFormValues,
} from './settlementDecisionForm';

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

  async function onSubmit(values: SettlementDecisionFormValues) {
    await decideSettlement(claimId, toApiRequest(values), attempt);
  }

  return (
    <form className="space-y-3 p-4" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
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
              <input
                className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm"
                placeholder="1500000.00"
                {...register('approvedAmount')}
              />
            </FormField>
            <FormField label="Currency" error={fieldError(errors, 'approvedCurrency')}>
              <input
                className="h-9 w-20 rounded-md border border-input bg-surface px-2.5 text-sm uppercase"
                {...register('approvedCurrency')}
              />
            </FormField>
          </div>
          <FormField label="Payee reference" error={fieldError(errors, 'payeeRef')}>
            <input
              className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm"
              placeholder="Mobile-money destination"
              {...register('payeeRef')}
            />
          </FormField>
        </>
      ) : (
        <FormField label="Rejection reason (optional)">
          <textarea
            className="min-h-16 w-full rounded-md border border-input bg-surface px-2.5 py-2 text-sm"
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

      <Button type="submit" size="sm" variant="primary" disabled={deciding.status === 'loading'}>
        {deciding.status === 'loading' ? 'Deciding…' : approved ? 'Approve claim' : 'Reject claim'}
      </Button>
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

function FormField({
  label,
  error,
  children,
}: {
  label: string;
  error?: string | undefined;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-medium text-muted-foreground">{label}</span>
      {children}
      {error && <p className="mt-1 text-[11px] text-status-danger-fg">{error}</p>}
    </label>
  );
}
