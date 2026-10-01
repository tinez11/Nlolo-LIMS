import { zodResolver } from '@hookform/resolvers/zod';
import { Plus, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { type FieldErrors, useFieldArray, useForm } from 'react-hook-form';
import type { ProductSummary } from '@/api/types';
import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectApplicablePlan, useDistributionStore } from '@/store/distributionStore';
import {
  blankCreateCommissionPlanForm,
  blankFlatRow,
  blankRateRow,
  createCommissionPlanFormSchema,
  PLANNABLE_TIER_TYPES,
  toApiRequest,
  type CreateCommissionPlanFormValues,
  type RuleRowFormValues,
} from './createCommissionPlanForm';
import { Input, Select } from '@/components/ui/input';
import { InlineError } from '@/components/InlineError';

/**
 * `GET /agents/{agentId}/commission-plan?productId=...` -- resolves the
 * agent's own assigned plan if it has one, else the product's ACTIVE plan.
 * There is no `GET /commission-plans/{id}`, so a plan is readable ONLY
 * through this lookup, never by its own id -- the same "write then no easy
 * read-back" shape as underwriting cases and DRAFT products.
 *
 * A genuine 404 here means no plan applies yet, not an error state -- staff
 * with FINANCE_OFFICER/ADMIN get an inline create form right where the 404
 * showed up, the same "read-attempt-then-create-fallback" idiom
 * ProductDetailPage/CreateProductPage already established for DRAFT products.
 */
export function CommissionPlanPanel({
  agentId,
  products,
  canManage,
}: {
  agentId: string;
  products: ProductSummary[];
  canManage: boolean;
}) {
  const [productId, setProductId] = useState('');
  const loadApplicablePlan = useDistributionStore((s) => s.loadApplicablePlan);
  const plan = useDistributionStore(selectApplicablePlan(agentId, productId));

  useEffect(() => {
    if (productId) void loadApplicablePlan(agentId, productId);
  }, [agentId, productId, loadApplicablePlan]);

  return (
    <div className="p-4">
      <label className="block">
        <span className="mb-1 block text-xs font-medium text-muted-foreground">Product</span>
        <Select
          value={productId}
          onChange={(e) => setProductId(e.target.value)}
        >
          <option value="">Select a product</option>
          {products.map((p) => (
            <option key={p.productId} value={p.productId}>
              {p.productName} ({p.productCode})
            </option>
          ))}
        </Select>
      </label>

      {productId && isInitialLoad(plan) && <LoadingBlock label="Resolving plan" />}

      {productId && plan.status === 'error' && plan.error?.kind === 'notFound' && (
        <div className="mt-3">
          <p className="mb-2 text-xs text-muted-foreground">
            No plan applies to this agent for this product yet.
          </p>
          {canManage && <CreatePlanForm key={productId} agentId={agentId} productId={productId} />}
        </div>
      )}

      {productId && plan.status === 'error' && plan.error && plan.error.kind !== 'notFound' && (
        <p className="mt-3 text-xs text-status-danger-fg">
          {plan.error.detail ?? plan.error.title}
        </p>
      )}

      {plan.data && (
        <div className="mt-3 space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-muted-foreground">Active plan</span>
            <StatusBadge kind="commissionPlan" value={plan.data.status} />
          </div>
          <dl>
            {plan.data.rules.map((rule) => (
              <Field
                key={rule.commissionRuleId}
                label={rule.tierType.replace(/_/g, ' ').toLowerCase()}
                value={
                  rule.flatAmount ? formatMoney(rule.flatAmount) : `${rule.rate ?? '—'} (rate)`
                }
              />
            ))}
          </dl>
        </div>
      )}
    </div>
  );
}

function CreatePlanForm({ agentId, productId }: { agentId: string; productId: string }) {
  const createCommissionPlan = useDistributionStore((s) => s.createCommissionPlan);
  const resetCreateCommissionPlan = useDistributionStore((s) => s.resetCreateCommissionPlan);
  const creating = useDistributionStore((s) => s.creatingPlan);

  // Same reset-on-mount discipline as every other single-slot mutation
  // resource on this console -- keyed here by remounting whenever the
  // selected product changes (the `key` prop on the call site), not by an
  // effect keyed on productId, since `creatingPlan` is a single global slot
  // shared across every product this panel might switch to.
  useEffect(() => {
    resetCreateCommissionPlan();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const {
    register,
    handleSubmit,
    control,
    watch,
    formState: { errors },
  } = useForm<CreateCommissionPlanFormValues>({
    resolver: zodResolver(createCommissionPlanFormSchema),
    defaultValues: blankCreateCommissionPlanForm(productId),
  });
  const { fields, append, remove, update } = useFieldArray({ control, name: 'rules' });

  // eslint-disable-next-line react-hooks/incompatible-library -- see IssuePolicyPage
  const rows = watch('rules');

  async function onSubmit(values: CreateCommissionPlanFormValues) {
    await createCommissionPlan(agentId, toApiRequest(values));
  }

  function setMode(index: number, mode: RuleRowFormValues['mode']) {
    const tierType = rows[index]?.tierType ?? 'FIRST_YEAR';
    update(index, mode === 'rate' ? blankRateRow(tierType) : blankFlatRow(tierType));
  }

  return (
    <form className="space-y-3 rounded-md border border-border p-3" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
      <p className="text-xs font-medium text-muted-foreground">Create a plan for this product</p>

      <div className="space-y-2">
        {fields.map((field, index) => {
          const row = rows[index];
          const mode = row?.mode ?? 'rate';
          return (
            <div key={field.id} className="rounded-md border border-border p-2.5">
              <div className="flex items-center gap-2">
                <Select
                  inputSize="sm"
                  {...register(`rules.${index}.tierType`)}
                >
                  {PLANNABLE_TIER_TYPES.map((t) => (
                    <option key={t} value={t}>
                      {t.replace(/_/g, ' ')}
                    </option>
                  ))}
                </Select>
                <Select
                  inputSize="sm"
                  value={mode}
                  onChange={(e) => setMode(index, e.target.value as RuleRowFormValues['mode'])}
                >
                  <option value="rate">Rate</option>
                  <option value="flat">Flat amount</option>
                </Select>
                <Button
                  type="button"
                  size="icon"
                  variant="ghost"
                  aria-label="Remove rule"
                  onClick={() => remove(index)}
                >
                  <X />
                </Button>
              </div>

              {mode === 'rate' ? (
                <Input
                  inputSize="sm" className="mt-2"
                  placeholder="0.1000 (10%)"
                  {...register(`rules.${index}.rate`)}
                />
              ) : (
                <div className="mt-2 flex gap-2">
                  <Input
                    inputSize="sm" className="flex-1"
                    placeholder="5000.00"
                    {...register(`rules.${index}.flatAmount`)}
                  />
                  <Input
                    inputSize="sm" className="w-20 uppercase"
                    {...register(`rules.${index}.flatCurrency`)}
                  />
                </div>
              )}

              {ruleError(errors, index) && (
                <p className="mt-1 text-xs text-status-danger-fg">{ruleError(errors, index)}</p>
              )}
            </div>
          );
        })}
      </div>

      <Button
        type="button"
        size="sm"
        variant="ghost"
        className="-ml-2"
        onClick={() => append(blankRateRow('FIRST_YEAR'))}
      >
        <Plus />
        Add rule
      </Button>

      {errors.rules?.root?.message && (
        <p className="text-xs text-status-danger-fg">{errors.rules.root.message}</p>
      )}

      {creating.status === 'error' && creating.error && (
        <InlineError error={creating.error} />
      )}

      <Button type="submit" size="sm" variant="primary" pending={creating.status === 'loading'}>
        Create plan
      </Button>
    </form>
  );
}

// react-hook-form's FieldErrors does not narrow per-branch on a discriminated
// union nested in a field array -- same limitation ClaimSettlementPanel hits
// at the top level, here one level deeper under `rules.${index}`.
function ruleError(errors: FieldErrors<CreateCommissionPlanFormValues>, index: number): string | undefined {
  const rec = errors.rules as unknown as Record<number, Record<string, { message?: string } | undefined>> | undefined;
  const row = rec?.[index];
  return row?.rate?.message ?? row?.flatAmount?.message ?? row?.flatCurrency?.message;
}
