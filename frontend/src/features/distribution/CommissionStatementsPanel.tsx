import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectAccruals,
  selectRequestingPayout,
  selectStatements,
  useDistributionStore,
} from '@/store/distributionStore';
import {
  blankRequestPayoutForm,
  requestPayoutFormSchema,
  toApiRequest,
  type RequestPayoutFormValues,
} from './requestPayoutForm';
import { Input } from '@/components/ui/input';

/**
 * `GET /agents/{agentId}/commission-statements` -- a bare array, no pager,
 * same shape as products' catalog listing. Statements only ever move
 * OPEN -> CLOSED via a monthly `pg_cron` sweep -- there is no manual "close"
 * action anywhere on this platform, so a freshly-accrued statement stays OPEN
 * (and therefore un-payable) for the rest of its accounting period, by
 * design, not because anything here is missing.
 *
 * "Request payout" is deliberately NOT disabled for an OPEN/PAID/
 * PAYOUT_REQUESTED statement client-side: `CommissionStatement.markPayoutRequested`
 * genuinely 409s outside CLOSED/PAYOUT_FAILED, and that is a real business
 * rejection worth showing plainly, the same call already made for claim
 * settlement decisions and policy issuance.
 */
export function CommissionStatementsPanel({ agentId, canManage }: { agentId: string; canManage: boolean }) {
  const [period, setPeriod] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);
  const loadStatements = useDistributionStore((s) => s.loadStatements);
  const statements = useDistributionStore(selectStatements(agentId));

  useEffect(() => {
    void loadStatements(agentId, period || undefined);
  }, [agentId, period, loadStatements]);

  const rows = statements.data ?? [];

  return (
    <div className="p-4">
      <label className="mb-3 block w-32">
        <span className="mb-1 block text-xs font-medium text-muted-foreground">Period (optional)</span>
        <Input
          inputSize="sm"
          placeholder="YYYY-MM"
          value={period}
          onChange={(e) => setPeriod(e.target.value)}
        />
      </label>

      {isInitialLoad(statements) && <LoadingBlock label="Loading statements" />}

      {statements.status === 'error' && statements.error && statements.data === null && (
        <ErrorPanel
          error={statements.error}
          onRetry={() => void loadStatements(agentId, period || undefined)}
        />
      )}

      {statements.status === 'success' && rows.length === 0 && (
        <p className="text-xs text-muted-foreground">No commission statements yet.</p>
      )}

      <div className="space-y-2">
        {rows.map((s) => (
          <div key={s.statementId} className="rounded-md border border-border p-2.5">
            <div className="flex items-center justify-between">
              <div>
                <span className="text-sm font-medium">{s.period}</span>
                <span className="ml-2 text-xs text-muted-foreground">{formatMoney(s.totalAmount)}</span>
              </div>
              <StatusBadge kind="commissionStatement" value={s.status} />
            </div>

            <Button
              size="sm"
              variant="ghost"
              className="-ml-2 mt-1"
              onClick={() => setExpanded(expanded === s.statementId ? null : s.statementId)}
            >
              {expanded === s.statementId ? 'Hide accruals' : 'View accruals'}
            </Button>

            {expanded === s.statementId && <AccrualsList agentId={agentId} statementId={s.statementId} />}

            {canManage && <PayoutForm agentId={agentId} statementId={s.statementId} />}
          </div>
        ))}
      </div>
    </div>
  );
}

function AccrualsList({ agentId, statementId }: { agentId: string; statementId: string }) {
  const loadAccruals = useDistributionStore((s) => s.loadAccruals);
  const accruals = useDistributionStore(selectAccruals(agentId, statementId));

  useEffect(() => {
    void loadAccruals(agentId, statementId);
  }, [agentId, statementId, loadAccruals]);

  if (isInitialLoad(accruals)) return <LoadingBlock label="Loading accruals" />;
  if (accruals.status === 'error' && accruals.error) {
    return <p className="mt-2 text-xs text-status-danger-fg">{accruals.error.detail ?? accruals.error.title}</p>;
  }
  const rows = accruals.data ?? [];
  if (rows.length === 0) {
    return <p className="mt-2 text-xs text-muted-foreground">No accrual line items.</p>;
  }

  return (
    <ul className="mt-2 space-y-1 border-t border-border pt-2">
      {rows.map((a) => (
        <li key={a.accrualId} className="flex items-center justify-between text-xs">
          <span className="text-muted-foreground">
            {a.tierType.replace(/_/g, ' ').toLowerCase()} · {a.policyNumber}
            {a.reversesAccrualId && ' · clawback'}
          </span>
          <span className={a.reversesAccrualId ? 'text-status-danger-fg' : undefined}>
            {formatMoney(a.amount)}
          </span>
        </li>
      ))}
    </ul>
  );
}

function PayoutForm({ agentId, statementId }: { agentId: string; statementId: string }) {
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
    resolver: zodResolver(requestPayoutFormSchema),
    defaultValues: blankRequestPayoutForm(),
  });

  async function onSubmit(values: RequestPayoutFormValues) {
    await requestPayout(agentId, statementId, toApiRequest(values), attempt);
  }

  return (
    <form
      className="mt-2 flex items-start gap-2 border-t border-border pt-2"
      onSubmit={(e) => void handleSubmit(onSubmit)(e)}
    >
      <div className="flex-1">
        <Input
          inputSize="sm"
          placeholder="Payee mobile-money reference"
          {...register('payeeRef')}
        />
        {errors.payeeRef?.message && (
          <p className="mt-1 text-[11px] text-status-danger-fg">{errors.payeeRef.message}</p>
        )}
        {requesting.status === 'error' && requesting.error && (
          <p role="alert" className="mt-1 text-[11px] text-status-danger-fg">
            {requesting.error.detail ?? requesting.error.title}
          </p>
        )}
      </div>
      <Button type="submit" size="sm" disabled={requesting.status === 'loading'}>
        {requesting.status === 'loading' ? 'Requesting…' : 'Request payout'}
      </Button>
    </form>
  );
}
