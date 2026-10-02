import { zodResolver } from '@hookform/resolvers/zod';
import { useForm, useWatch } from 'react-hook-form';
import type { DepositPeriodView, DepositView } from '@/api/types';
import { Field } from '@/components/Field';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { formatDate } from '@/lib/dates';
import { startMutation } from '@/lib/idempotency';
import { formatMoney } from '@/lib/money';
import { useAccumulationStore } from '@/store/accumulationStore';
import { instructionSchema, payeeSchema, toInstructionBody, type InstructionValues, type PayeeValues } from './depositForms';

const STATUS_LABEL: Record<DepositPeriodView['status'], string> = {
  RUNNING: 'Running',
  MATURED: 'Matured',
  TERMINATED: 'Ended early',
  CANCELLED: 'Cancelled',
};

/**
 * A fixed-term deposit (2026-10-02): the running term and its rate FOR THE TERM, every term before
 * it, and what happens at maturity. Nothing can be added or taken out, so no movement is offered;
 * the panel says so instead of showing buttons the server would refuse.
 */
export function DepositSection({ deposit, isFinance }: { deposit: DepositView; isFinance: boolean }) {
  const running = deposit.periods.find((p) => p.status === 'RUNNING');
  return (
    <div className="space-y-4">
      {running && (
        <>
          <dl className="divide-y divide-border rounded-md border border-border" aria-label="Running term">
            <Field label="Deposit" value={formatMoney(running.principal)} emphasis />
            <Field label="Term" value={`${running.termMonths} months, ${running.ratePercent}% for the term`} />
            <Field label="Started" value={formatDate(running.startDate)} />
            <Field label="Matures" value={formatDate(running.maturityDate)} />
            <Field label="Interest earned so far" value={formatMoney(deposit.interestSoFar)} />
          </dl>
          <p className="text-xs text-muted-foreground">
            This is a fixed-term deposit: nothing can be added or taken out until it matures on{' '}
            {formatDate(running.maturityDate)}.
          </p>
          <MaturityPanel deposit={deposit} />
        </>
      )}
      {deposit.awaitingPayee &&
        (isFinance ? (
          <PayeePanel policyNumber={deposit.policyNumber} />
        ) : (
          <p className="text-sm">This deposit has matured and waits for finance to record who is paid.</p>
        ))}
      <div>
        <p className="mb-1.5 text-xs font-medium">Terms</p>
        <div className="divide-y divide-border rounded-md border border-border" role="list" aria-label="Deposit terms">
          {deposit.periods.map((p) => (
            <div key={p.periodId} role="listitem" className="flex items-start justify-between gap-3 px-4 py-2.5">
              <div className="min-w-0">
                <span className="text-sm font-medium">
                  Term {p.seq} · {STATUS_LABEL[p.status]}
                </span>
                <p className="text-xs text-muted-foreground">
                  {formatDate(p.startDate)} to {formatDate(p.maturityDate)} · {p.termMonths} months at {p.ratePercent}%
                </p>
              </div>
              <span className="shrink-0 text-right">
                <span className="block text-sm font-medium">{formatMoney(p.principal)}</span>
                {p.interestPosted && (
                  <span className="block text-xs text-muted-foreground">interest {formatMoney(p.interestPosted)}</span>
                )}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function describeInstruction(deposit: DepositView): string {
  const fallback = deposit.defaultPayeeRef ?? 'a payee finance records';
  const current = deposit.instruction;
  if (!current) return `No instruction: it will be paid out to ${fallback}.`;
  return current.action === 'REINVEST'
    ? `Reinvest for ${current.termMonths} months (recorded by ${current.recordedBy}).`
    : `Pay out to ${current.payeeRef ?? fallback} (recorded by ${current.recordedBy}).`;
}

function MaturityPanel({ deposit }: { deposit: DepositView }) {
  const instruct = useAccumulationStore((s) => s.instruct);
  const acting = useAccumulationStore((s) => s.acting[`instruct.${deposit.policyNumber}`]);
  const form = useForm<InstructionValues>({
    resolver: zodResolver(instructionSchema),
    defaultValues: { action: 'PAY_OUT', termMonths: '', payeeRef: '' },
  });
  const action = useWatch({ control: form.control, name: 'action' });
  const errors = form.formState.errors;

  return (
    <form
      className="space-y-3 rounded-md border border-border p-3"
      aria-label="At maturity"
      onSubmit={form.handleSubmit((values) => void instruct(deposit.policyNumber, toInstructionBody(values), startMutation()))}
    >
      <p className="text-xs font-medium text-muted-foreground">At maturity</p>
      <p className="text-sm">{describeInstruction(deposit)}</p>
      <div className="grid grid-cols-2 gap-3">
        <FormField label="What should happen" error={errors.action?.message}>
          <Select inputSize="sm" {...form.register('action')}>
            <option value="PAY_OUT">Pay out</option>
            <option value="REINVEST">Reinvest the deposit and its interest</option>
          </Select>
        </FormField>
        {action === 'REINVEST' ? (
          <FormField label="New term" error={errors.termMonths?.message}>
            <Select inputSize="sm" {...form.register('termMonths')}>
              <option value="">Choose…</option>
              {deposit.termsOffered.map((t) => (
                <option key={t} value={String(t)}>
                  {t} months
                </option>
              ))}
            </Select>
          </FormField>
        ) : (
          <FormField label="Pay to (blank: the number it came from)" error={errors.payeeRef?.message}>
            <Input inputSize="sm" {...form.register('payeeRef')} />
          </FormField>
        )}
      </div>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
        Record instruction
      </Button>
    </form>
  );
}

function PayeePanel({ policyNumber }: { policyNumber: string }) {
  const payOut = useAccumulationStore((s) => s.payOutDeposit);
  const acting = useAccumulationStore((s) => s.acting[`depositPayout.${policyNumber}`]);
  const form = useForm<PayeeValues>({ resolver: zodResolver(payeeSchema), defaultValues: { payeeRef: '' } });

  return (
    <form
      className="space-y-3 rounded-md border border-border p-3"
      aria-label="Record payee and pay"
      onSubmit={form.handleSubmit((values) => void payOut(policyNumber, values.payeeRef, startMutation()))}
    >
      <p className="text-sm">This deposit has matured and there is no number to pay it to.</p>
      <FormField label="Pay to" error={form.formState.errors.payeeRef?.message}>
        <Input inputSize="sm" {...form.register('payeeRef')} />
      </FormField>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      {acting?.status === 'success' && (
        <p className="text-xs text-muted-foreground" role="status">
          The payment has been requested from the provider.
        </p>
      )}
      <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
        Record payee and pay
      </Button>
    </form>
  );
}
