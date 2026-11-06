import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ConfirmAct } from '@/components/ConfirmAct';
import { Field } from '@/components/Field';
import { FormField, RequiredNote } from '@/components/FormField';
import { FormLayout } from '@/components/FormLayout';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { UnsavedGuard } from '@/components/UnsavedGuard';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { withdrawalSchema, type WithdrawalValues } from '@/features/accounts/accountForms';
import { PayoutRouteFields } from '@/features/payments/PayoutRouteFields';
import { methodWords } from '@/features/payments/paymentWords';
import { BLANK_ROUTE, describeRoute, routeBody } from '@/features/payments/payoutRoute';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectBalance, usePolicyStore } from '@/store/policyStore';

/**
 * Withdraw from a policy's balance (money movement step 5, design §7.1): a full page, as Record deposit is. One
 * person asks (payouts.manage) and nothing moves until a second person approves it on the policy's Balance panel --
 * or the same person, holding payouts.approve-own, recorded against their name.
 */
export function BalanceWithdrawalPage() {
  const { policyNumber = '' } = useParams();
  const navigate = useNavigate();
  const balance = usePolicyStore(selectBalance(policyNumber));
  const loadBalance = usePolicyStore((s) => s.loadBalance);
  const request = usePolicyStore((s) => s.requestBalanceWithdrawal);
  const asking = usePolicyStore((s) => s.actingOnWithdrawal[policyNumber]);
  const [armed, setArmed] = useState<WithdrawalValues | null>(null);
  const form = useForm<WithdrawalValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(withdrawalSchema),
    defaultValues: { amount: '', ...BLANK_ROUTE },
  });
  const back = `/staff/policies/${encodeURIComponent(policyNumber)}?tab=billing`;

  useEffect(() => {
    void loadBalance(policyNumber);
  }, [policyNumber, loadBalance]);

  if (isInitialLoad(balance)) return <LoadingBlock />;
  if (balance.status === 'error' && balance.error && balance.data === null) {
    return <ErrorPanel error={balance.error} onRetry={() => void loadBalance(policyNumber)} />;
  }
  if (!balance.data) return null;
  const currency = balance.data.balance.currencyCode;

  async function send(values: WithdrawalValues) {
    setArmed(null);
    const asked = await request(policyNumber, {
      amount: { amount: values.amount, currencyCode: currency },
      payee: routeBody(values),
    });
    if (asked) navigate(back);
  }

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Policies', to: '/staff/policies' }, { label: policyNumber, to: back }]}
        title="Withdraw"
        description="Pay money back off the policy's balance. Nothing moves until a second person approves it."
      />
      <FormLayout formId="balance-withdrawal-form" outlineLabel="Withdrawal sections">
        <form className="space-y-4" onSubmit={(e) => void form.handleSubmit((v) => setArmed(v))(e)}>
          <RequiredNote />
          <UnsavedGuard when={form.formState.isDirty && !armed && asking?.status !== 'loading'} what="This withdrawal" />
          <div data-outline="How much" className="space-y-4">
            <dl className="grid grid-cols-1 gap-x-6 sm:grid-cols-2">
              <Field label="On the balance now" value={formatMoney(balance.data.balance)} />
            </dl>
            <div className="max-w-xs">
              <FormField required
                label={`Amount (${currency})`}
                hint="At most what is on the balance; it is checked again when a second person approves it."
                error={form.formState.errors.amount?.message}
              >
                <Input {...form.register('amount')} inputMode="decimal" />
              </FormField>
            </div>
          </div>
          <div data-outline="Who receives it" className="space-y-4">
            <PayoutRouteFields form={form} />
          </div>

          {asking?.status === 'error' && asking.error && (
            <InlineError lead="Could not ask for the withdrawal" error={asking.error} />
          )}

          {armed ? (
            <ConfirmAct
              heading="Ask for this withdrawal?"
              consequence={
                <>
                  <strong>{formatMoney({ amount: armed.amount, currencyCode: currency })}</strong> from{' '}
                  <strong>{policyNumber}</strong>, paid by {methodWords(armed.method).toLowerCase()} to{' '}
                  <strong>{describeRoute(routeBody(armed))}</strong>.
                </>
              }
              reversal="Nothing leaves the balance until a second person approves it. It can be rejected until then."
              confirmLabel="Ask for withdrawal"
              busy={asking?.status === 'loading'}
              onConfirm={() => void send(armed)}
              onCancel={() => setArmed(null)}
            />
          ) : (
            <div className="flex gap-2">
              <Button type="submit" variant="primary">
                Continue
              </Button>
              <Button asChild variant="ghost">
                <Link to={back}>Cancel</Link>
              </Button>
            </div>
          )}
        </form>
      </FormLayout>
    </>
  );
}
