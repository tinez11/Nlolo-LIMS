import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import type { CommissionStatementView } from '@/api/types';
import { ConfirmAct } from '@/components/ConfirmAct';
import { InlineError } from '@/components/InlineError';
import { Receipt } from '@/components/Receipt';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { formatMoney } from '@/lib/money';
import { selectRequestingPayout, useDistributionStore } from '@/store/distributionStore';
import {
  blankRequestPayoutForm,
  requestPayoutFormSchema,
  toApiRequest,
  type RequestPayoutFormValues,
} from './requestPayoutForm';

/**
 * Staff FINANCE_OFFICER/ADMIN payout of one statement. Moved out of CommissionStatementsPanel
 * unchanged when that panel became AgentCommission (spec 2026-10-12).
 *
 * Deliberately NOT disabled for an OPEN/PAID/PAYOUT_REQUESTED statement:
 * `CommissionStatement.markPayoutRequested` genuinely 409s outside CLOSED/PAYOUT_FAILED, and that
 * is a real business rejection worth showing plainly, the same call already made for claim
 * settlement decisions and policy issuance.
 */
export function PayoutForm({
  agentId,
  statement,
}: {
  agentId: string;
  statement: CommissionStatementView;
}) {
  const statementId = statement.statementId ?? '';
  const requestPayout = useDistributionStore((s) => s.requestPayout);
  const resetRequestPayout = useDistributionStore((s) => s.resetRequestPayout);
  const requesting = useDistributionStore(selectRequestingPayout(agentId, statementId));
  // Minted once per mount and reused across retries, same idiom as every
  // other MutationAttempt on this console (RegisterClaimPage et al.) -- a
  // genuinely new intent (e.g. retrying after PAYOUT_FAILED with the "MUST
  // use a NEW Idempotency-Key" rule the spec documents) naturally gets a
  // fresh key by navigating away and back, which remounts this component;
  // there is no per-row remount trigger to re-mint one mid-mount.
  const [attempt] = useState<MutationAttempt>(() => startMutation());

  useEffect(() => {
    resetRequestPayout(agentId, statementId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId, statementId]);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<RequestPayoutFormValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(requestPayoutFormSchema),
    defaultValues: blankRequestPayoutForm(),
  });

  // Validated values wait here for the second click -- see ClaimSettlementPanel
  // for why validation has to come first.
  const [pending, setPending] = useState<RequestPayoutFormValues | null>(null);

  async function commit(values: RequestPayoutFormValues) {
    await requestPayout(agentId, statementId, toApiRequest(values), attempt);
    // Through the selector, not the raw map: this resource is keyed by a
    // composite `agentId:statementId` built by a module-private helper, so
    // re-spelling that format here would be a silent drift waiting to happen.
    if (selectRequestingPayout(agentId, statementId)(useDistributionStore.getState()).status === 'success') {
      setPending(null);
    }
  }

  if (requesting.status === 'success') {
    return (
      <div className="mt-2 border-t border-border pt-2">
        <Receipt
          heading="Payout requested"
          lines={[
            { label: 'Period', value: statement.period ?? '—' },
            { label: 'Amount', value: formatMoney(statement.totalAmount) },
          ]}
          /*
            `POST .../payout` answers 202 with NO BODY, so there is no
            server-issued reference to show and none is invented. Period and
            amount come off the statement this panel already loaded, which is
            real server data; everything else here is about what has not
            happened yet, and says so. Calling this "Paid" would be the lie --
            the disbursement settles later through payment's confirm loop.
          */
          note="Requested, not yet paid: the disbursement settles asynchronously and the statement moves to PAID when payment confirms. A retry after a failure needs a fresh attempt — reopen this agent to mint one."
        />
      </div>
    );
  }

  return (
    <form
      className="mt-2 space-y-2 border-t border-border pt-2"
      onSubmit={(e) => void handleSubmit(setPending)(e)}
    >
      <div className="flex items-start gap-2">
        <div className="flex-1">
          <Input
            inputSize="sm"
            aria-label="Payee mobile-money reference"
            placeholder="Payee mobile-money reference"
            {...register('payeeRef')}
          />
          {errors.payeeRef?.message && (
            <p className="mt-1 text-xs text-status-danger-fg">{errors.payeeRef.message}</p>
          )}
          {requesting.status === 'error' && requesting.error && (
            <InlineError error={requesting.error} />
          )}
        </div>
        {!pending && (
          <Button type="submit" size="sm">
            Request payout
          </Button>
        )}
      </div>

      {pending && (
        <ConfirmAct
          heading="Pay out this statement?"
          tone="danger"
          consequence={
            <>
              Send <strong>{formatMoney(statement.totalAmount)}</strong> for{' '}
              <strong>{statement.period}</strong> to <strong>{pending.payeeRef}</strong>.
            </>
          }
          // The retry rule is the spec's own, not a guess: payment dedupes on
          // the Idempotency-Key and silently drops a resubmission carrying the
          // old one, so a failed payout genuinely cannot be retried from this
          // mounted form.
          reversal="Money leaves through the payment rail and cannot be recalled from this console. A failed payout cannot be retried from this form — it needs a new attempt."
          confirmLabel="Pay out"
          busy={requesting.status === 'loading'}
          onConfirm={() => void commit(pending)}
          onCancel={() => setPending(null)}
        />
      )}
    </form>
  );
}
