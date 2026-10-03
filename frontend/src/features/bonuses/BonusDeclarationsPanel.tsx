import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { useAuth } from 'react-oidc-context';
import type { BonusDeclarationView } from '@/api/types';
import { canAuthorProducts, readIdentity } from '@/auth/claims';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { InlineError } from '@/components/InlineError';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { approveDeclarationGates } from '@/gates/bonusGates';
import { formatDate } from '@/lib/dates';
import { startMutation } from '@/lib/idempotency';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useBonusStore } from '@/store/bonusStore';
import { bonusDeclarationSchema, type BonusDeclarationValues } from './bonusDeclarationForm';

/**
 * Bonus declarations for a with-profits product (product step 4).
 *
 * A declaration attaches its reversionary rate to every policy eligible on its valuation date, and
 * sets the terminal rate paid at a claim or maturity valued against it. Proposing is ADMIN's -- a
 * price, like publishing a version; approving needs a different ADMIN or a finance officer. One
 * approved declaration per product and valuation date: the server's refusal says so in its words.
 */
export function BonusDeclarationsPanel({ productId }: { productId: string }) {
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const viewerSubject = identity?.subject ?? undefined;
  const canPropose = identity ? canAuthorProducts(identity) : false;

  const declarations = useBonusStore((s) => s.declarations[productId]);
  const loadDeclarations = useBonusStore((s) => s.loadDeclarations);

  useEffect(() => {
    void loadDeclarations(productId);
  }, [productId, loadDeclarations]);

  if (!declarations || isInitialLoad(declarations)) return <LoadingBlock />;
  const rows = declarations.data ?? [];

  return (
    <div className="space-y-4 p-4">
      {rows.length === 0 ? (
        <EmptyState
          title="No bonus declared"
          description="Policies on this product receive bonuses only when a declaration is approved and its valuation date arrives."
        />
      ) : (
        <div className="divide-y divide-border rounded-md border border-border" role="list" aria-label="Bonus declarations">
          {rows.map((d) => (
            <DeclarationRow key={d.declarationId} productId={productId} declaration={d} viewerSubject={viewerSubject} canPropose={canPropose} />
          ))}
        </div>
      )}
      {canPropose && <ProposeForm productId={productId} />}
    </div>
  );
}

function DeclarationRow({
  productId,
  declaration,
  viewerSubject,
  canPropose,
}: {
  productId: string;
  declaration: BonusDeclarationView;
  viewerSubject: string | undefined;
  canPropose: boolean;
}) {
  const approve = useBonusStore((s) => s.approveDeclaration);
  const withdraw = useBonusStore((s) => s.withdrawDeclaration);
  const acting = useBonusStore((s) => s.acting[declaration.declarationId]);
  const gates = approveDeclarationGates(declaration, viewerSubject);
  const refused = gates.some((g) => !g.ok && g.hard);

  return (
    <div role="listitem" className="space-y-2 px-4 py-2.5">
      <div>
        <span className="text-sm font-medium">
          {declaration.reversionaryRatePercent}% reversionary · {declaration.terminalRatePercent}% terminal
        </span>{' '}
        <StatusBadge kind="bonusDeclaration" value={declaration.status} />
        <p className="text-xs text-muted-foreground">
          As at {formatDate(declaration.valuationDate)} · proposed by {declaration.proposedBy}
          {declaration.approvedBy ? ` · approved by ${declaration.approvedBy}` : ''}
          {declaration.completedAt ? ` · attached ${formatDate(declaration.completedAt)}` : ''}
        </p>
      </div>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      {declaration.status === 'PROPOSED' && (
        <>
          <GatePanel gates={gates} title="Before approving" />
          <div className="flex gap-2">
            <Button
              size="sm"
              disabled={refused || acting?.status === 'loading'}
              onClick={() => void approve(productId, declaration.declarationId, startMutation())}
            >
              Approve declaration
            </Button>
            {canPropose && (
              <Button
                size="sm"
                variant="ghost"
                disabled={acting?.status === 'loading'}
                onClick={() => void withdraw(productId, declaration.declarationId, startMutation())}
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
  const propose = useBonusStore((s) => s.proposeDeclaration);
  const acting = useBonusStore((s) => s.acting[`declaration.${productId}`]);
  const form = useForm<BonusDeclarationValues>({
    resolver: zodResolver(bonusDeclarationSchema),
    defaultValues: { valuationDate: '', reversionaryRatePercent: '', terminalRatePercent: '' },
  });

  return (
    <form
      className="space-y-3 rounded-md border border-border p-3"
      onSubmit={form.handleSubmit((v) =>
        void propose(
          productId,
          {
            valuationDate: v.valuationDate,
            reversionaryRatePercent: Number(v.reversionaryRatePercent),
            terminalRatePercent: Number(v.terminalRatePercent),
          },
          startMutation(),
        ).then(() => {
          if (useBonusStore.getState().acting[`declaration.${productId}`]?.status === 'success') form.reset();
        }),
      )}
    >
      <p className="text-xs font-medium">Declare a bonus</p>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <FormField label="Valuation date" error={form.formState.errors.valuationDate?.message}>
          <Controller
            control={form.control}
            name="valuationDate"
            render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
          />
        </FormField>
        <FormField label="Reversionary rate (%)" error={form.formState.errors.reversionaryRatePercent?.message}>
          <Input inputMode="decimal" placeholder="3" {...form.register('reversionaryRatePercent')} />
        </FormField>
        <FormField label="Terminal rate (% of attached bonuses)" error={form.formState.errors.terminalRatePercent?.message}>
          <Input inputMode="decimal" placeholder="50" {...form.register('terminalRatePercent')} />
        </FormField>
      </div>
      <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
        Propose declaration
      </Button>
    </form>
  );
}
