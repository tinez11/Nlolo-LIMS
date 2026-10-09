import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { useAuth } from 'react-oidc-context';
import type { RateDeclarationView } from '@/api/types';
import { canAuthorProducts, readIdentity } from '@/auth/claims';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { InlineError } from '@/components/InlineError';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { approveRateGates } from '@/gates/accumulationGates';
import { formatDate } from '@/lib/dates';
import { startMutation } from '@/lib/idempotency';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useAccumulationStore } from '@/store/accumulationStore';
import { rateDeclarationSchema, type RateDeclarationValues } from './rateDeclarationForm';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';


/**
 * Declared interest rates for a savings product (product step 3).
 *
 * Every account on the product is credited at the HIGHER of the approved declared rate and its own
 * version's guarantee. Proposing is ADMIN's -- a price, like publishing a version; approving needs a
 * different ADMIN or a finance officer. The server also refuses a rate that would take effect on or
 * before interest it has already credited; that is not a gate here, because this page cannot see the
 * latest month-end, so the refusal is shown in the server's own words instead.
 */
export function RateDeclarationsPanel({ productId }: { productId: string }) {
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const viewerSubject = identity?.subject ?? undefined;
  const canPropose = identity ? canAuthorProducts(identity) : false;

  const rates = useAccumulationStore((s) => s.rates[productId]);
  const loadRates = useAccumulationStore((s) => s.loadRates);

  useEffect(() => {
    void loadRates(productId);
  }, [productId, loadRates]);

  if (!rates || isInitialLoad(rates)) return <LoadingBlock />;
  const rows = rates.data ?? [];

  return (
    <div className="space-y-4 p-4">
      {rows.length === 0 ? (
        <EmptyState
          title="No rate declared"
          description="Accounts on this product are credited at their own version's guaranteed rate until a rate is declared and approved."
        />
      ) : (
        <div className="divide-y divide-border rounded-md border border-border" role="list" aria-label="Declared rates">
          {rows.map((rate) => (
            <RateRow key={rate.declarationId} productId={productId} rate={rate} viewerSubject={viewerSubject} canPropose={canPropose} />
          ))}
        </div>
      )}
      {canPropose && <ProposeForm productId={productId} />}
    </div>
  );
}

function RateRow({
  productId,
  rate,
  viewerSubject,
  canPropose,
}: {
  productId: string;
  rate: RateDeclarationView;
  viewerSubject: string | undefined;
  canPropose: boolean;
}) {
  const approve = useAccumulationStore((s) => s.approveRate);
  const withdraw = useAccumulationStore((s) => s.withdrawRate);
  const acting = useAccumulationStore((s) => s.acting[rate.declarationId]);
  const gates = approveRateGates(rate, viewerSubject);
  const refused = gates.some((g) => !g.ok && g.hard);

  return (
    <div role="listitem" className="space-y-2 px-4 py-2.5">
      <div>
        <span className="text-sm font-medium">{rate.ratePercent}% a year</span>{' '}
        <StatusBadge kind="rateDeclaration" value={rate.status} />
        <p className="text-xs text-muted-foreground">
          From {formatDate(rate.effectiveFrom)} · proposed by {rate.proposedBy}
          {rate.approvedBy ? ` · approved by ${rate.approvedBy}` : ''}
        </p>
      </div>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      {rate.status === 'PROPOSED' && (
        <>
          <GatePanel gates={gates} title="Before approving" />
          <div className="flex gap-2">
            <Button
              size="sm"
              disabled={refused || acting?.status === 'loading'}
              onClick={() => void approve(productId, rate.declarationId, startMutation())}
            >
              Approve rate
            </Button>
            {canPropose && (
              <Button
                size="sm"
                variant="ghost"
                disabled={acting?.status === 'loading'}
                onClick={() => void withdraw(productId, rate.declarationId, startMutation())}
              >
                Withdraw
              </Button>
            )}
          </div>
        </>
      )}
    </div>
  );
}

function ProposeForm({ productId }: { productId: string }) {
  const propose = useAccumulationStore((s) => s.proposeRate);
  const acting = useAccumulationStore((s) => s.acting[`rate.${productId}`]);
  const form = useForm<RateDeclarationValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(rateDeclarationSchema),
    defaultValues: { ratePercent: '', effectiveFrom: '' },
  });

  return (
    <form
      className="space-y-3 rounded-md border border-border p-3"
      onSubmit={form.handleSubmit((v) =>
        void propose(productId, { ratePercent: Number(v.ratePercent), effectiveFrom: v.effectiveFrom }, startMutation()).then(() => {
          if (useAccumulationStore.getState().acting[`rate.${productId}`]?.status === 'success') form.reset();
        }),
      )}
    >
      <p className="text-xs font-medium">Declare a rate</p>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <div className="grid grid-cols-2 gap-3">
        <FormField label="Rate (% a year)" error={form.formState.errors.ratePercent?.message}>
          <Input inputMode="decimal" placeholder="6.5" {...form.register('ratePercent')} />
        </FormField>
        <FormField label="Effective from" error={form.formState.errors.effectiveFrom?.message}>
          <Controller
            control={form.control}
            name="effectiveFrom"
            render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
          />
        </FormField>
      </div>
      <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
        Propose rate
      </Button>
    </form>
  );
}
