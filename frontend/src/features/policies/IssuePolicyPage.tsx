import { ArrowLeft, Plus } from 'lucide-react';
import { useEffect } from 'react';
import { useFieldArray, useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate } from 'react-router-dom';
import { PREMIUM_FREQUENCIES } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { DatePicker } from '@/components/DatePicker';
import { PartyPicker } from '@/components/PartyPicker';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { BeneficiaryRow } from './BeneficiaryRow';
import { formatDate, todayIso } from '@/lib/dates';
import { UUID_PATTERN } from '@/lib/patterns';
import { hasHardFailure, issueGates, softBreaches } from '@/gates/issueGates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { usePolicyStore } from '@/store/policyStore';
import { selectParty, usePartyStore } from '@/store/partyStore';
import { selectAgent, useDistributionStore } from '@/store/distributionStore';
import { selectProductSnapshot, useProductStore } from '@/store/productStore';
import { blankBeneficiaryRow } from './beneficiaryForm';
import {
  blankPolicyIssueForm,
  maturityPreview,
  policyIssueFormSchema,
  toApiRequest,
  type PolicyIssueFormInput,
  type PolicyIssueFormValues,
} from './policyIssueForm';
import { Input, Select } from '@/components/ui/input';

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

  // Display only: Policy.applyTerm derives and stores the value that counts.
  const maturity = maturityPreview(watch('commencementDate'), watch('policyTermMonths'));

  // ---- Preconditions -------------------------------------------------------
  // PLAN.md §14.3: a mutating surface declares its preconditions before it renders
  // a submit button. The gates are a pure function of already-fetched records, so
  // everything here is fetching, never deciding.
  const policyholderPartyId = watch('policyholderPartyId');
  const agentOfRecordId = watch('agentOfRecordId');
  const commencementDate = watch('commencementDate');
  const policyTermMonths = watch('policyTermMonths');
  const sumAssuredAmount = watch('sumAssuredAmount');

  const loadParty = usePartyStore((s) => s.loadParty);
  const policyholder = usePartyStore(selectParty(policyholderPartyId));
  useEffect(() => {
    if (policyholderPartyId) void loadParty(policyholderPartyId);
  }, [policyholderPartyId, loadParty]);

  const loadAgent = useDistributionStore((s) => s.loadAgent);
  const agent = useDistributionStore(selectAgent(agentOfRecordId));
  useEffect(() => {
    // Only a well-formed id is worth a request; the field accepts free text.
    if (agentOfRecordId && UUID_PATTERN.test(agentOfRecordId)) void loadAgent(agentOfRecordId);
  }, [agentOfRecordId, loadAgent]);

  const snapshot = useProductStore(selectProductSnapshot(productId));

  const gates = issueGates({
    snapshot: snapshot.data ?? null,
    policyholder: policyholder.data ?? null,
    agent: agent.data ?? null,
    commencementDate: commencementDate || null,
    policyTermMonths: policyTermMonths ? Number(policyTermMonths) : null,
    sumAssured: sumAssuredAmount ? Number(sumAssuredAmount) : null,
    today: todayIso(),
  });
  const blocked = hasHardFailure(gates);
  const breaches = softBreaches(gates);

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
        {/* Labelled by meaning, not by column name. The policyholder owns the contract;
            the life assured below is whose death the policy pays on, and on most life
            business those are two different people. */}
        <FormField label="Policyholder" error={errors.policyholderPartyId?.message}>
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

        <FormField
          label="Life assured (leave blank if the policyholder insures themselves)"
          error={errors.lifeAssuredPartyId?.message}
        >
          <Controller
            control={control}
            name="lifeAssuredPartyId"
            render={({ field }) => (
              <PartyPicker
                value={field.value || null}
                onChange={(partyId) => field.onChange(partyId ?? '')}
                placeholder="Search for the life assured by name"
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
            <Select
              {...register('productId')}
            >
              <option value="">Select a product</option>
              {(products.data ?? []).map((p) => (
                <option key={p.productId} value={p.productId}>
                  {p.productName} ({p.productCode})
                </option>
              ))}
            </Select>
          )}
          {productId && isInitialLoad(snapshot) && (
            <p className="mt-1 text-[11px] text-muted-foreground">Resolving product version…</p>
          )}
        </FormField>

        <div className="grid grid-cols-[1fr_auto] gap-2">
          <FormField label="Sum assured" error={errors.sumAssuredAmount?.message}>
            <Input
              placeholder="2000000.00"
              {...register('sumAssuredAmount')}
            />
          </FormField>
          <FormField label="Currency" error={errors.sumAssuredCurrency?.message}>
            <Input
              className="w-20 uppercase"
              {...register('sumAssuredCurrency')}
            />
          </FormField>
        </div>

        <div className="grid grid-cols-[1fr_auto] gap-2">
          <FormField label="Premium" error={errors.premiumAmount?.message}>
            <Input
              placeholder="800.00"
              {...register('premiumAmount')}
            />
          </FormField>
          <FormField label="Currency" error={errors.premiumCurrency?.message}>
            <Input
              className="w-20 uppercase"
              {...register('premiumCurrency')}
            />
          </FormField>
        </div>

        <FormField label="Premium frequency">
          <Select
            {...register('premiumFrequency')}
          >
            {PREMIUM_FREQUENCIES.map((f) => (
              <option key={f} value={f}>
                {f}
              </option>
            ))}
          </Select>
        </FormField>

        {/* The policy term. Optional as a group: whole life, an annuity and an
            annually renewable group scheme genuinely have none, so leaving these blank
            is a real answer rather than an omission. */}
        <fieldset className="border-t border-border pt-3">
          <legend className="pr-2 text-[11px] font-medium tracking-wide text-subtle-foreground uppercase">
            Term
          </legend>
          <p className="mb-2.5 text-[11px] text-muted-foreground">
            Leave blank for a product that does not term — whole life, an annuity, a
            renewable group scheme.
          </p>

          <div className="grid grid-cols-2 gap-3">
            <FormField label="Commencement date" error={errors.commencementDate?.message}>
              <Controller
                control={control}
                name="commencementDate"
                render={({ field }) => (
                  <DatePicker
                    value={field.value || null}
                    onChange={(iso) => field.onChange(iso ?? '')}
                  />
                )}
              />
            </FormField>

            <FormField label="Policy term (months)" error={errors.policyTermMonths?.message}>
              <Input
                inputMode="numeric"
                
                placeholder="240"
                {...register('policyTermMonths')}
              />
            </FormField>

            <FormField
              label="Premium-paying term (months)"
              error={errors.premiumPayingTermMonths?.message}
            >
              <Input
                inputMode="numeric"
                
                placeholder="Same as the policy term"
                {...register('premiumPayingTermMonths')}
              />
            </FormField>

            {/* The consequence of "240 months", legible before submit. Display only --
                the aggregate derives and stores the real value. */}
            <div className="self-end pb-1">
              <p className="text-[11px] text-muted-foreground">Matures</p>
              <p className="text-sm tabular-nums">
                {maturity ? (
                  formatDate(maturity)
                ) : (
                  <span className="text-subtle-foreground">—</span>
                )}
              </p>
            </div>
          </div>
        </fieldset>

        <FormField label="Agent of record id (optional)" error={errors.agentOfRecordId?.message}>
          <Input
            className="font-mono"
            placeholder="uuid, or leave blank for a direct/online policy"
            {...register('agentOfRecordId')}
          />
        </FormField>

        <GatePanel gates={gates} title="Before issuing" />

        <FormField label="Reason for manual issue" error={errors.reasonForManualIssue?.message}>
          <Input
            placeholder={
              breaches.length > 0
                ? 'Say why the flagged check above is acceptable'
                : 'Feeds the audit trail'
            }
            {...register('reasonForManualIssue')}
          />
          {/* The only place a soft breach gets recorded. Without this the panel is a
              warning staff learn to click past, which launders the decision rather
              than capturing it. */}
          {breaches.length > 0 && (
            <p className="mt-1 text-[11px] text-status-warning-fg">
              {breaches.length === 1
                ? 'One check above is flagged — this reason is where that decision is recorded.'
                : `${breaches.length} checks above are flagged — this reason is where those decisions are recorded.`}
            </p>
          )}
        </FormField>

        <div className="rounded-md border border-border p-3">
          <p className="mb-2 text-xs font-medium text-muted-foreground">Beneficiaries (optional)</p>
          <div className="space-y-3">
            {fields.map((field, index) => {
              const type = beneficiaryRows[index]?.type ?? 'PARTY';
              const rowError = errors.beneficiaries?.[index];
              return (
                <BeneficiaryRow
                  key={field.id}
                  type={type}
                  typeField={register(`beneficiaries.${index}.type`)}
                  designeeField={register(`beneficiaries.${index}.freeformDesignee`)}
                  shareField={register(`beneficiaries.${index}.sharePercent`)}
                  revocableField={register(`beneficiaries.${index}.revocable`)}
                  party={
                    <Controller
                      control={control}
                      name={`beneficiaries.${index}.partyId`}
                      render={({ field: partyField }) => (
                        <PartyPicker
                          value={partyField.value || null}
                          onChange={(partyId) => partyField.onChange(partyId ?? '')}
                          placeholder="Search for the beneficiary by name"
                        />
                      )}
                    />
                  }
                  onRemove={() => remove(index)}
                  /* Previously rendered NOTHING for a rejected row -- the panel copy
                     of this markup showed the message and this one silently did not,
                     which is the drift that made extracting it worth doing. */
                  error={
                    rowError?.partyId?.message ??
                    rowError?.freeformDesignee?.message ??
                    rowError?.sharePercent?.message
                  }
                />
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
          {/* A hard gate means the platform will refuse this anyway -- an age with no
              rate cell cannot be priced, and a term the product does not offer is the
              wrong product. Disabling states that here rather than after a round trip.
              A SOFT breach never disables: above retention is cedeable business, and
              refusing it in the console would be the UI declining a case the insurer
              would write. */}
          <Button
            type="submit"
            variant="primary"
            disabled={issuing.status === 'loading' || blocked}
            {...(blocked
              ? { title: 'A check above must pass before this policy can be issued' }
              : {})}
          >
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
