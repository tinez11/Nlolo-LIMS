import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { selectReopening, useClaimStore } from '@/store/claimStore';
import {
  blankReopenClaimForm,
  reopenClaimFormSchema,
  toApiRequest,
  type ReopenClaimFormValues,
} from './reopenClaimForm';
import { Textarea } from '@/components/ui/input';
import { InlineError } from '@/components/InlineError';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';

/**
 * `POST /claims/{claimId}/reopen` -- `CLAIMS_MANAGER` role only, rendered by
 * the parent only for a claim currently REJECTED or SETTLED (any other status
 * 409s). Deliberately does not clear the prior approved amount -- the previous
 * decision stays on the record until a new one is made (`Claim.reopen()`'s own
 * doc). Reopening a SETTLED claim does NOT reverse the policy closure
 * settlement caused (a documented platform limitation, not a bug this panel
 * can paper over) -- surfaced as a note rather than silently implied to work.
 */
export function ClaimReopenPanel({ claimId, wasSettled }: { claimId: string; wasSettled: boolean }) {
  const reopenClaim = useClaimStore((s) => s.reopenClaim);
  const resetReopenClaim = useClaimStore((s) => s.resetReopenClaim);
  const reopening = useClaimStore(selectReopening(claimId));

  useEffect(() => {
    resetReopenClaim(claimId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [claimId]);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ReopenClaimFormValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(reopenClaimFormSchema),
    defaultValues: blankReopenClaimForm(),
  });

  async function onSubmit(values: ReopenClaimFormValues) {
    await reopenClaim(claimId, toApiRequest(values));
  }

  return (
    <form className="space-y-3 p-4" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
      {wasSettled && (
        <p className="rounded-md bg-status-warning-bg px-3 py-2 text-xs text-status-warning-fg">
          This claim is SETTLED. Reopening it does not reverse the policy closure settlement already
          caused — coverage stays discharged and billing stays stopped.
        </p>
      )}

      <FormField label="Reason" error={errors.reason?.message}>
        <Textarea
          className="min-h-16"
          placeholder="New evidence submitted"
          {...register('reason')}
        />
      </FormField>

      {reopening.status === 'error' && reopening.error && (
        <InlineError error={reopening.error} />
      )}

      <Button type="submit" size="sm" variant="primary" pending={reopening.status === 'loading'}>
        Reopen claim
      </Button>
    </form>
  );
}
