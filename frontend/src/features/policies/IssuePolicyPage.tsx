import { ArrowLeft, Plus, X } from 'lucide-react';
import { useEffect } from 'react';
import { useFieldArray, useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate } from 'react-router-dom';
import { PREMIUM_FREQUENCIES } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { PartyPicker } from '@/components/PartyPicker';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { isInitialLoad } from '@/store/createResourceSlice';
import { usePolicyStore } from '@/store/policyStore';
import { selectProductSnapshot, useProductStore } from '@/store/productStore';
import { blankBeneficiaryRow } from './beneficiaryForm';
import {
  blankPolicyIssueForm,
  policyIssueFormSchema,
  toApiRequest,
  type PolicyIssueFormInput,
  type PolicyIssueFormValues,
} from './policyIssueForm';

/**
 * `POST /policies/manual-issue` -- the staff exception path. Unlike every other
 * mutation in this console, it declares NO `Idempotency-Key` at all (confirmed
 * against both the controller and the spec): a second identical submission
 * genuinely creates a second policy. The submit button disabling while in
 * flight is therefore not a UX nicety here, it is the ONLY guard against a
 * double-click producing two policies -- there is no server-side backstop.
 */
export function IssuePolicyPage() {
  const navigate = useNavigate();

  const issuePolicy = usePolicyStore((s) => s.issuePolicy);
  const resetIssuePolicy = usePolicyStore((s) => s.resetIssuePolicy);
  const issuing = usePolicyStore((s) => s.issuing);

  const products = useProductStore((s) => s.list);
  const loadProducts = useProductStore((s) => s.loadList);
  const loadSnapshot = useProductStore((s) => s.loadSnapshot);

  // Same reset-on-mount discipline as RegisterClaimPage, for the same reason:
  // `issuing` is a single slot that outlives this page's own mount/unmount, so a
  // previous visit's rejection would otherwise resurface immediately.
  useEffect(() => {
    resetIssuePolicy();
    void loadProducts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Three generics: Input (raw, pre-coercion -- what register()/watch() see),
  // Context (unused), Output (post-coercion -- what handleSubmit's callback
  // receives). Same reason as BeneficiariesPanel: the embedded
  // beneficiaryListSchema's z.coerce.number() gives sharePercent different
  // input/output types.
  const {
    register,
    handleSubmit,
    watch,
    setValue,
    control,
    formState: { errors },
  } = useForm<PolicyIssueFormInput, unknown, PolicyIssueFormValues>({
    resolver: zodResolver(policyIssueFormSchema),
    defaultValues: blankPolicyIssueForm(),
  });
  const { fields, append, remove } = useFieldArray({ control, name: 'beneficiaries' });

  // eslint-disable-next-line react-hooks/incompatible-library -- see RegisterClaimPage
  const productId = watch('productId');
  const beneficiaryRows = watch('beneficiaries');

  const snapshot = useProductStore(selectProductSnapshot(productId));

  // Resolving productVersionId is a side effect of picking a product, not
  // something the user fills in directly -- there is no screen anywhere that
  // shows a product's version id for them to paste.
  useEffect(() => {
    if (!productId) return;
    void loadSnapshot(productId);
  }, [productId, loadSnapshot]);

  // defaultCurrency lives on ProductSummary (the catalog listing), not
  // ProductSnapshot (the version lookup) -- two different schemas, so this reads
  // from the already-loaded product list rather than the snapshot.
  const selectedProduct = (products.data ?? []).find((p) => p.productId === productId);

  useEffect(() => {
    if (snapshot.data?.productVersionId) {
      setValue('productVersionId', snapshot.data.productVersionId);
    }
    if (selectedProduct?.defaultCurrency) {
      setValue('sumAssuredCurrency', selectedProduct.defaultCurrency);
      setValue('premiumCurrency', selectedProduct.defaultCurrency);
    }
  }, [snapshot.data, selectedProduct, setValue]);

  const total = beneficiaryRows.reduce((sum, r) => {
    const value = Number(r.sharePercent);
    return sum + (Number.isFinite(value) ? value : 0);
  }, 0);
  const totalOk = Math.abs(total - 100) <= 0.005 || beneficiaryRows.length === 0;

  async function onSubmit(values: PolicyIssueFormValues) {
    await issuePolicy(toApiRequest(values));
    const result = usePolicyStore.getState().issuing;
    if (result.status === 'success' && result.data?.policyNumber) {
      navigate(`../${result.data.policyNumber}`, { relative: 'path' });
    }
  }

  return (
    <>
      <div className="px-6 pt-6">
        <Button asChild variant="ghost" size="sm" className="-ml-2">
          <Link to=".." relative="path">
            <ArrowLeft />
            All policies
          </Link>
        </Button>
      </div>

      <PageHeader
        title="Issue a policy"
        description="The staff exception path -- outside the normal underwriting-decision pipeline."
      />

      <form className="max-w-xl space-y-4 px-6 pb-8" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
        <FormField label="Policyholder party id" error={errors.policyholderPartyId?.message}>
          <Controller
            control={control}
            name="policyholderPartyId"
            render={({ field }) => (
              <PartyPicker
                value={field.value || null}
                onChange={(partyId) => field.onChange(partyId ?? '')}
                placeholder="Search for the policyholder by name"
              />
            )}
          />
        </FormField>

        <FormField label="Product" error={errors.productId?.message}>
          {isInitialLoad(products) ? (
            <p className="text-xs text-muted-foreground">Loading products…</p>
          ) : products.status === 'error' ? (
            <p className="text-xs text-status-danger-fg">Could not load products.</p>
          ) : (
            <select
              className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm"
              {...register('productId')}
            >
              <option value="">Select a product</option>
              {(products.data ?? []).map((p) => (
                <option key={p.productId} value={p.productId}>
                  {p.productName} ({p.productCode})
                </option>
              ))}
            </select>
          )}
          {productId && isInitialLoad(snapshot) && (
            <p className="mt-1 text-[11px] text-muted-foreground">Resolving product version…</p>
          )}
        </FormField>

        <div className="grid grid-cols-[1fr_auto] gap-2">
          <FormField label="Sum assured" error={errors.sumAssuredAmount?.message}>
            <input
              className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm"
              placeholder="2000000.00"
              {...register('sumAssuredAmount')}
            />
          </FormField>
          <FormField label="Currency" error={errors.sumAssuredCurrency?.message}>
            <input
              className="h-9 w-20 rounded-md border border-input bg-surface px-2.5 text-sm uppercase"
              {...register('sumAssuredCurrency')}
            />
          </FormField>
        </div>

        <div className="grid grid-cols-[1fr_auto] gap-2">
          <FormField label="Premium" error={errors.premiumAmount?.message}>
            <input
              className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm"
              placeholder="800.00"
              {...register('premiumAmount')}
            />
          </FormField>
          <FormField label="Currency" error={errors.premiumCurrency?.message}>
            <input
              className="h-9 w-20 rounded-md border border-input bg-surface px-2.5 text-sm uppercase"
              {...register('premiumCurrency')}
            />
          </FormField>
        </div>

        <FormField label="Premium frequency">
          <select
            className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm"
            {...register('premiumFrequency')}
          >
            {PREMIUM_FREQUENCIES.map((f) => (
              <option key={f} value={f}>
                {f}
              </option>
            ))}
          </select>
        </FormField>

        <FormField label="Agent of record id (optional)" error={errors.agentOfRecordId?.message}>
          <input
            className="h-9 w-full rounded-md border border-input bg-surface px-2.5 font-mono text-sm"
            placeholder="uuid, or leave blank for a direct/online policy"
            {...register('agentOfRecordId')}
          />
        </FormField>

        <FormField label="Reason for manual issue" error={errors.reasonForManualIssue?.message}>
          <input
            className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm"
            placeholder="Feeds the audit trail"
            {...register('reasonForManualIssue')}
          />
        </FormField>

        <div className="rounded-md border border-border p-3">
          <p className="mb-2 text-xs font-medium text-muted-foreground">Beneficiaries (optional)</p>
          <div className="space-y-3">
            {fields.map((field, index) => {
              const type = beneficiaryRows[index]?.type ?? 'PARTY';
              return (
                <div key={field.id} className="rounded-md border border-border p-2.5">
                  <div className="flex items-center gap-2">
                    <select
                      className="h-8 rounded-md border border-input bg-surface px-2 text-xs"
                      {...register(`beneficiaries.${index}.type`)}
                    >
                      <option value="PARTY">Party</option>
                      <option value="FREEFORM">Freeform</option>
                    </select>
                    {type === 'PARTY' ? (
                      <Controller
                        control={control}
                        name={`beneficiaries.${index}.partyId`}
                        render={({ field }) => (
                          <div className="h-8 flex-1">
                            <PartyPicker
                              value={field.value || null}
                              onChange={(partyId) => field.onChange(partyId ?? '')}
                              placeholder="Search for the beneficiary by name"
                            />
                          </div>
                        )}
                      />
                    ) : (
                      <input
                        className="h-8 flex-1 rounded-md border border-input bg-surface px-2 text-xs"
                        placeholder={'Designee, e.g. "My Estate"'}
                        {...register(`beneficiaries.${index}.freeformDesignee`)}
                      />
                    )}
                    <div className="flex items-center gap-1">
                      <input
                        type="number"
                        min={0}
                        max={100}
                        step="0.01"
                        className="h-8 w-20 rounded-md border border-input bg-surface px-2 text-right text-xs"
                        {...register(`beneficiaries.${index}.sharePercent`)}
                      />
                      <span className="text-xs text-muted-foreground">%</span>
                    </div>
                    <Button
                      type="button"
                      size="icon"
                      variant="ghost"
                      aria-label="Remove beneficiary"
                      onClick={() => remove(index)}
                    >
                      <X />
                    </Button>
                  </div>
                </div>
              );
            })}
          </div>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            className="-ml-2 mt-2"
            onClick={() => append(blankBeneficiaryRow())}
          >
            <Plus />
            Add beneficiary
          </Button>
          <p className={`mt-2 text-xs ${totalOk ? 'text-muted-foreground' : 'text-status-danger-fg'}`}>
            Total: {total}% {!totalOk && '— must sum to 100% (or be empty)'}
          </p>
        </div>

        {/* No Idempotency-Key on this endpoint at all -- a real 422 here (e.g. an
            unresolvable party or product version) is a genuine, whole-request
            rejection worth showing plainly. */}
        {issuing.status === 'error' && issuing.error && (
          <div role="alert" className="rounded-md bg-status-danger-bg px-3 py-2 text-xs text-status-danger-fg">
            {issuing.error.detail ?? issuing.error.title}
            {issuing.error.traceId && (
              <span className="ml-2 font-mono text-[10px] opacity-80">({issuing.error.traceId})</span>
            )}
          </div>
        )}

        <div className="flex items-center gap-2">
          <Button type="submit" variant="primary" disabled={issuing.status === 'loading'}>
            {issuing.status === 'loading' ? 'Issuing…' : 'Issue policy'}
          </Button>
          <Button asChild variant="ghost">
            <Link to=".." relative="path">
              Cancel
            </Link>
          </Button>
        </div>
      </form>
    </>
  );
}
