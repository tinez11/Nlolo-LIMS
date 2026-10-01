import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useFieldArray, useForm } from 'react-hook-form';
import { useAuth } from 'react-oidc-context';
import type { FreeLookCancellationView, PolicyView } from '@/api/types';
import { canSeeFinance, readIdentity } from '@/auth/claims';
import { ConfirmAct } from '@/components/ConfirmAct';
import { Field } from '@/components/Field';
import { FormField } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { InlineError } from '@/components/InlineError';
import { Receipt } from '@/components/Receipt';
import { StatusBadge } from '@/components/StatusBadge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import type { Gate } from '@/gates/types';
import { formatDate } from '@/lib/dates';
import { startMutation } from '@/lib/idempotency';
import { formatMoney } from '@/lib/money';
import { useBenefitPayoutStore } from '@/store/benefitPayoutStore';
import { blankDeduction, blankFreeLook, freeLookSchema, type FreeLookValues } from './freeLookForm';

/**
 * Free-look cancellation: the customer changed their mind, and the sale is undone from inception
 * (guide §21.3).
 *
 * Not a surrender, and the panel says so. A surrender ends a contract that RAN — the customer gets
 * a value. Free-look treats them as never having bought it, so what goes back is the premiums less
 * what the insurer actually spent.
 *
 * Preparing is open to any staff member; releasing the money is finance's, exactly as the
 * endpoints are.
 */
export function FreeLookPanel({ policy }: { policy: PolicyView }) {
  const policyNumber = policy.policyNumber ?? '';
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const viewerSubject = identity?.subject ?? undefined;
  const isFinance = identity ? canSeeFinance(identity) : false;

  const cancellation = useBenefitPayoutStore((s) => s.freeLook[policyNumber]);
  const loadFreeLook = useBenefitPayoutStore((s) => s.loadFreeLook);

  useEffect(() => {
    if (policyNumber) void loadFreeLook(policyNumber);
  }, [policyNumber, loadFreeLook]);

  const existing = cancellation?.data ?? null;

  // Nothing to show at all: not cancellable and never cancelled. The panel stays out of the way
  // rather than explaining an action that was never available on this record.
  if (!existing && policy.status !== 'ACTIVE') return null;

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <p className="text-xs font-medium">Free-look cancellation</p>
        {existing?.status && <StatusBadge kind="freeLookCancellation" value={existing.status} />}
      </div>

      {existing ? (
        <DecidedOrAwaiting
          cancellation={existing}
          policyNumber={policyNumber}
          viewerSubject={viewerSubject}
          isFinance={isFinance}
        />
      ) : (
        <RequestForm policy={policy} />
      )}
    </div>
  );
}

function RequestForm({ policy }: { policy: PolicyView }) {
  const policyNumber = policy.policyNumber ?? '';
  const requestCancellation = useBenefitPayoutStore((s) => s.requestCancellation);
  const acting = useBenefitPayoutStore((s) => s.acting[`freelook.${policyNumber}`]);
  const [armed, setArmed] = useState<FreeLookValues | null>(null);

  const form = useForm<FreeLookValues>({
    resolver: zodResolver(freeLookSchema),
    defaultValues: blankFreeLook(),
  });
  const deductions = useFieldArray({ control: form.control, name: 'deductions' });

  /*
    The window's LENGTH lives on the product version, which this console cannot read — so the gate
    states only what PolicyView proves: the date the window is counted from. Claiming a last day
    computed from a number nobody here has would be inventing a judgement, which is exactly what
    the gate doctrine forbids. The server knows the number and refuses accordingly.
  */
  const gates: Gate[] = [
    {
      ok: policy.status === 'ACTIVE',
      hard: true,
      title: 'Inside the free-look window',
      detail:
        policy.status === 'ACTIVE'
          ? `Counted from the issue date, ${formatDate(policy.issueDate)}. The product sets the number of days and the server checks the exact one.`
          : 'Only an ACTIVE policy can be cancelled in its free-look period.',
    },
  ];
  const refused = gates.some((g) => !g.ok && g.hard);

  const onSubmit = form.handleSubmit((values) => setArmed(values));

  if (armed) {
    return (
      <ConfirmAct
        heading="Cancel this policy in its free-look period?"
        consequence={
          <>
            <strong>{policyNumber}</strong> is treated as never having been bought, and the premiums
            less {armed.deductions.length} deduction
            {armed.deductions.length === 1 ? '' : 's'} go back to{' '}
            <strong>{armed.payeeRef}</strong>. The server works out the refund — this panel shows
            the figure once it has.
          </>
        }
        reversal="Once a second person approves, cover is void from inception and the policy cannot be revived. Until then nothing changes."
        confirmLabel="Request cancellation"
        tone="danger"
        busy={acting?.status === 'loading'}
        onConfirm={() => {
          void requestCancellation(
            policyNumber,
            {
              payeeRef: armed.payeeRef.trim(),
              deductions: armed.deductions.map((d) => ({
                description: d.description.trim(),
                amount: d.amount,
                documentId: null,
              })),
            },
            startMutation(),
          );
          setArmed(null);
        }}
        onCancel={() => setArmed(null)}
      />
    );
  }

  return (
    <div className="space-y-3">
      <GatePanel gates={gates} title="Before cancelling" />
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <form onSubmit={onSubmit} className="space-y-3">
        <FormField label="Refund to" error={form.formState.errors.payeeRef?.message}>
          <Input
            {...form.register('payeeRef')}
            placeholder="Mobile money number or bank destination"
            disabled={refused}
          />
        </FormField>

        <div className="space-y-2">
          <p className="text-xs text-muted-foreground">
            What the insurer spent getting this policy on the books. Itemised, so the customer can
            be told what each one was for. Leave empty to refund everything.
          </p>
          {deductions.fields.map((field, index) => (
            <div key={field.id} className="flex items-end gap-2">
              <FormField
                label="What for"
                error={form.formState.errors.deductions?.[index]?.description?.message}
              >
                <Input
                  {...form.register(`deductions.${index}.description`)}
                  placeholder="Medical examination"
                  disabled={refused}
                />
              </FormField>
              <FormField
                label="Amount"
                error={form.formState.errors.deductions?.[index]?.amount?.message}
              >
                <Input
                  {...form.register(`deductions.${index}.amount`)}
                  inputMode="decimal"
                  placeholder="8000.00"
                  disabled={refused}
                />
              </FormField>
              <Button
                type="button"
                size="sm"
                variant="ghost"
                onClick={() => deductions.remove(index)}
                aria-label={`Remove deduction ${index + 1}`}
              >
                Remove
              </Button>
            </div>
          ))}
          <Button
            type="button"
            size="sm"
            variant="ghost"
            className="-ml-2"
            disabled={refused}
            onClick={() => deductions.append(blankDeduction())}
          >
            Add a deduction
          </Button>
        </div>

        <Button type="submit" size="sm" disabled={refused}>
          Cancel in free-look
        </Button>
      </form>
    </div>
  );
}

function DecidedOrAwaiting({
  cancellation,
  policyNumber,
  viewerSubject,
  isFinance,
}: {
  cancellation: FreeLookCancellationView;
  policyNumber: string;
  viewerSubject: string | undefined;
  isFinance: boolean;
}) {
  const approveCancellation = useBenefitPayoutStore((s) => s.approveCancellation);
  const acting = useBenefitPayoutStore((s) => s.acting[`freelook.${policyNumber}`]);
  const [armed, setArmed] = useState(false);

  const figures = (
    <dl className="divide-y divide-border rounded-md border border-border">
      <Field label="Premiums collected" value={formatMoney(cancellation.premiumsCollected)} />
      {cancellation.deductions.map((deduction, index) => (
        <Field
          key={`${deduction.description}-${index}`}
          label={`Less ${deduction.description}`}
          value={formatMoney(deduction.amount)}
        />
      ))}
      <Field label="Refund" value={formatMoney(cancellation.refundAmount)} emphasis />
      <Field label="Refund to" value={cancellation.payeeRef} />
      <Field label="Requested by" value={cancellation.requestedBy} />
      {cancellation.approvedBy && <Field label="Approved by" value={cancellation.approvedBy} />}
    </dl>
  );

  if (cancellation.status !== 'REQUESTED') {
    return (
      <div className="space-y-2">
        {acting?.status === 'success' && (
          <Receipt
            heading="Refund requested"
            lines={[
              { label: 'Policy', value: policyNumber },
              { label: 'Refunding', value: formatMoney(cancellation.refundAmount) },
              { label: 'To', value: cancellation.payeeRef },
            ]}
            note="Cover is void from inception and the refund has been requested, not paid. This panel shows PAID once the payment provider confirms it."
          />
        )}
        {figures}
      </div>
    );
  }

  const sameAsRequester = viewerSubject !== undefined && viewerSubject === cancellation.requestedBy;
  const gates: Gate[] = [
    {
      ok: !sameAsRequester,
      hard: true,
      title: 'A second person approves',
      detail: sameAsRequester
        ? `A free-look cancellation must be approved by someone other than the person who requested it (${cancellation.requestedBy})`
        : 'You did not prepare this cancellation.',
    },
  ];
  const refused = gates.some((g) => !g.ok && g.hard);

  return (
    <div className="space-y-3">
      {figures}
      {/* Preparing is open to any staff member; releasing the money is finance's, so a
          non-finance viewer sees the figures and is told who can act, rather than a button
          that would 403. */}
      {!isFinance ? (
        <p className="text-xs text-muted-foreground">
          Awaiting approval by a finance officer. The refund is released by someone other than
          whoever prepared it.
        </p>
      ) : (
        <>
          <GatePanel gates={gates} title="Before approving" />
          {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
          {armed ? (
            <ConfirmAct
              heading="Approve this free-look cancellation?"
              consequence={
                <>
                  <strong>{policyNumber}</strong> is void from inception and{' '}
                  <strong>{formatMoney(cancellation.refundAmount)}</strong> is requested for{' '}
                  {cancellation.payeeRef}.
                </>
              }
              reversal="Cover is void from inception and the policy cannot be revived. Full cover would mean a new application, underwritten again."
              confirmLabel={`Refund ${formatMoney(cancellation.refundAmount)}`}
              tone="danger"
              busy={acting?.status === 'loading'}
              onConfirm={() => {
                void approveCancellation(cancellation.cancellationId, policyNumber, startMutation());
                setArmed(false);
              }}
              onCancel={() => setArmed(false)}
            />
          ) : (
            <Button size="sm" disabled={refused} onClick={() => setArmed(true)}>
              Approve cancellation
            </Button>
          )}
        </>
      )}
    </div>
  );
}
