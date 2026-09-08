import { ArrowLeft, Plus } from 'lucide-react';
import { useEffect } from 'react';
import { useFieldArray, useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate } from 'react-router-dom';
import { PageHeader } from '@/components/PageHeader';
import { DatePicker } from '@/components/DatePicker';
import { PartyPicker } from '@/components/PartyPicker';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { PREMIUM_FREQUENCIES } from '@/api/types';
import { humanizeStatus } from '@/lib/status';
import { BeneficiaryRow } from '@/features/policies/BeneficiaryRow';
import { blankBeneficiaryRow } from '@/features/policies/beneficiaryForm';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectProductSnapshot, useProductStore } from '@/store/productStore';
import { useUnderwritingStore } from '@/store/underwritingStore';
import {
  blankOpenCaseForm,
  openCaseFormSchema,
  toApiRequest,
  type OpenCaseFormInput,
  type OpenCaseFormValues,
} from './openCaseForm';
import { Input, Select } from '@/components/ui/input';

/**
 * `POST /underwriting/cases` -- the only entry point onto this domain that
 * exists server-side. `GET /underwriting/cases` (the Underwriting queue) is now
 * a real browse-back path, but a policy's own `underwritingCaseId` still never
 * round-trips back out through `GET /policies` (confirmed: the actual wire DTO,
 * `PolicyResponseDto`, omits it entirely, and so does the OpenAPI spec's
 * `PolicyView` response schema -- only the internal, same-named domain record
 * carries it). So this page's own success response is still the fastest way to
 * this id, even though it is no longer the only one.
 */
export function OpenUnderwritingCasePage() {
  const navigate = useNavigate();

  const openCase = useUnderwritingStore((s) => s.openCase);
  const resetOpenCase = useUnderwritingStore((s) => s.resetOpenCase);
  const opening = useUnderwritingStore((s) => s.opening);

  const products = useProductStore((s) => s.list);
  const loadProducts = useProductStore((s) => s.loadList);
  const loadSnapshot = useProductStore((s) => s.loadSnapshot);

  // Same reset-on-mount discipline as every other single-slot mutation resource
  // on this console: `opening` outlives this page's own mount/unmount, so a
  // previous visit's rejection would otherwise resurface immediately.
  useEffect(() => {
    resetOpenCase();
    void loadProducts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const {
    register,
    handleSubmit,
    watch,
    setValue,
    control,
    formState: { errors },
  } = useForm<OpenCaseFormInput, unknown, OpenCaseFormValues>({
    resolver: zodResolver(openCaseFormSchema),
    defaultValues: blankOpenCaseForm(),
  });
  const {
    fields: beneficiaryFields,
    append: appendBeneficiary,
    remove: removeBeneficiary,
  } = useFieldArray({ control, name: 'beneficiaries' });

  // eslint-disable-next-line react-hooks/incompatible-library -- see IssuePolicyPage
  const productId = watch('productId');
  const beneficiaryRows = watch('beneficiaries');

  // Derived from the live rows, not stored: this console's lint bans synchronous setState in
  // an effect, and a second copy of the total could only ever disagree with the rows.
  const beneficiaryTotal = beneficiaryRows.reduce((sum, r) => {
    const value = Number(r.sharePercent);
    return sum + (Number.isFinite(value) ? value : 0);
  }, 0);
  const beneficiaryTotalOk =
    Math.abs(beneficiaryTotal - 100) <= 0.005 || beneficiaryRows.length === 0;
  const snapshot = useProductStore(selectProductSnapshot(productId));

  useEffect(() => {
    if (!productId) return;
    void loadSnapshot(productId);
  }, [productId, loadSnapshot]);

  const selectedProduct = (products.data ?? []).find((p) => p.productId === productId);

  useEffect(() => {
    if (snapshot.data?.productVersionId) {
      setValue('productVersionId', snapshot.data.productVersionId);
    }
    if (selectedProduct?.defaultCurrency) {
      setValue('sumAssuredCurrency', selectedProduct.defaultCurrency);
    }
  }, [snapshot.data, selectedProduct, setValue]);

  async function onSubmit(values: OpenCaseFormValues) {
    await openCase(toApiRequest(values));
    const result = useUnderwritingStore.getState().opening;
    if (result.status === 'success' && result.data?.caseId) {
      navigate(`../${result.data.caseId}`, { relative: 'path' });
    }
  }

  return (
    <>
      <div className="px-6 pt-6">
        <Button asChild variant="ghost" size="sm" className="-ml-2">
          <Link to=".." relative="path">
            <ArrowLeft />
            Back
          </Link>
        </Button>
      </div>

      <PageHeader
        title="Open an underwriting case"
        description="Browsable afterward from the Underwriting queue -- but a policy later issued from it never re-exposes this case's id."
      />

      <form className="max-w-xl space-y-4 px-6 pb-8" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
        {/* "Applicant" is who proposes; "life assured" is whose mortality is assessed.
            Labelled by what they mean rather than by the column name, which is the
            broader problem this console still has elsewhere. */}
        <FormField label="Applicant" error={errors.applicantPartyId?.message}>
          <Controller
            control={control}
            name="applicantPartyId"
            render={({ field }) => (
              <PartyPicker
                value={field.value || null}
                onChange={(partyId) => field.onChange(partyId ?? '')}
                placeholder="Search for the applicant by name"
              />
            )}
          />
        </FormField>

        <FormField
          label="Life assured (leave blank if the applicant insures themselves)"
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
              placeholder="1500000.00"
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

        {/* Optional, and consequential: a decision that accepts this case issues the policy
            automatically, and distribution accrues no commission at all for a policy with no
            agent of record. Leaving it blank records a direct sale, which is a real thing
            and not a default to fall into by accident. */}
        <FormField label="Agent of record id (optional)" error={errors.agentOfRecordId?.message}>
          <Input
            className="font-mono text-xs"
            placeholder="00000000-0000-0000-0000-000000000000"
            {...register('agentOfRecordId')}
          />
          <p className="mt-1 text-[11px] text-subtle-foreground">
            Who sold it. Leave blank for a direct sale — a policy issued with no agent of record
            accrues no commission.
          </p>
        </FormField>

        {/* Where the business came from. Last and grouped: all three are optional, and the
            risk — who, what product, how much — is what the form is actually for. */}
        <fieldset className="border-t border-border pt-3">
          <legend className="pr-2 text-[11px] font-medium tracking-wide text-subtle-foreground uppercase">
            Source
          </legend>

          <div className="grid grid-cols-2 gap-3">
            <FormField label="Branch" error={errors.branch?.message}>
              <Input
                {...register('branch')}
              />
            </FormField>

            <FormField label="Source of business" error={errors.sourceOfBusiness?.message}>
              <Input
                placeholder="e.g. Bancassurance"
                {...register('sourceOfBusiness')}
              />
            </FormField>

            <div className="col-span-2">
              <FormField
                label="Proposed commencement date"
                error={errors.proposedCommencementDate?.message}
              >
                <Controller
                  control={control}
                  name="proposedCommencementDate"
                  render={({ field }) => (
                    <DatePicker
                      value={field.value || null}
                      onChange={(iso) => field.onChange(iso ?? '')}
                    />
                  )}
                />
              </FormField>
            </div>
          </div>
        </fieldset>

        {/*
          What the applicant asks for about the CONTRACT, as distinct from the risk above.

          These only ever existed on the manual issue form, which made it the only screen able
          to produce a complete policy: one issued on the normal path had no term, no maturity
          date -- it is derived from commencement plus term -- and nobody nominated, because
          nobody had ever asked. That is very likely why staff reached for manual issue.

          All optional. A product that does not term genuinely has none, and a proposal taken
          with the nomination blank is routine rather than incomplete.
        */}
        <fieldset className="space-y-4">
          <legend className="text-sm font-medium">What the applicant is asking for</legend>

          <div className="grid grid-cols-2 gap-4">
            <FormField label="Term (months)" error={errors.requestedTermMonths?.message}>
              <Input placeholder="120" {...register('requestedTermMonths')} />
            </FormField>

            <FormField
              label="Premium-paying term (months)"
              error={errors.premiumPayingTermMonths?.message}
            >
              <Input placeholder="Same as the term" {...register('premiumPayingTermMonths')} />
            </FormField>

            <FormField label="Premium frequency" error={errors.premiumFrequency?.message}>
              <Select {...register('premiumFrequency')}>
                {/* Blank first and selected by default: a frequency the applicant did not
                    state is not monthly, and it is what the issued policy is billed on. */}
                <option value="">Not stated</option>
                {PREMIUM_FREQUENCIES.map((f) => (
                  <option key={f} value={f}>
                    {humanizeStatus(f)}
                  </option>
                ))}
              </Select>
            </FormField>
          </div>

          {/*
            The same rows the issue form uses, from the same component and the same schema.
            Not a second nomination editor: the two lists have to mean exactly the same thing
            for the issuance listener to MAP one to the other rather than interpret it.
          */}
          <div className="rounded-md border border-border p-3">
            <p className="mb-2 text-xs font-medium text-muted-foreground">
              Beneficiary nominations (optional)
            </p>
            <div className="space-y-3">
              {beneficiaryFields.map((field, index) => {
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
                    onRemove={() => removeBeneficiary(index)}
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
              onClick={() => appendBeneficiary(blankBeneficiaryRow())}
            >
              <Plus />
              Add beneficiary
            </Button>
            <p
              className={`mt-2 text-xs ${
                beneficiaryTotalOk ? 'text-muted-foreground' : 'text-status-danger-fg'
              }`}
            >
              Total: {beneficiaryTotal}%{' '}
              {!beneficiaryTotalOk && '— must sum to 100% (or be empty)'}
            </p>
          </div>
        </fieldset>

        {opening.status === 'error' && opening.error && (
          <div role="alert" className="rounded-md bg-status-danger-bg px-3 py-2 text-xs text-status-danger-fg">
            {opening.error.detail ?? opening.error.title}
            {opening.error.traceId && (
              <span className="ml-2 font-mono text-[10px] opacity-80">({opening.error.traceId})</span>
            )}
          </div>
        )}

        <div className="flex items-center gap-2">
          <Button type="submit" variant="primary" disabled={opening.status === 'loading'}>
            {opening.status === 'loading' ? 'Opening…' : 'Open case'}
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
