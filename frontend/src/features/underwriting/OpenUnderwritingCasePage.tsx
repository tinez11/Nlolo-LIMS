import { AccountChargePicker } from '@/features/products/AccountChargePicker';
import { Plus } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useFieldArray, useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate } from 'react-router-dom';
import { PageHeader } from '@/components/PageHeader';
import { DatePicker } from '@/components/DatePicker';
import { PartyPicker } from '@/components/PartyPicker';
import { PartyName } from '@/components/PartyName';
import { AgentPicker } from '@/components/AgentPicker';
import { BranchSelect } from '@/components/BranchSelect';
import { CHANNEL_LABEL } from '@/lib/ifrs17';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { isSingleLifeProduct, PREMIUM_FREQUENCIES, PREMIUM_FREQUENCY_LABELS } from '@/api/types';
import { BeneficiaryRow } from '@/features/policies/BeneficiaryRow';
import { blankBeneficiaryRow } from '@/features/policies/beneficiaryForm';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectProductSnapshot, useProductStore } from '@/store/productStore';
import { selectParty, usePartyStore } from '@/store/partyStore';
import { useUnderwritingStore } from '@/store/underwritingStore';
import {
  blankOpenCaseForm,
  openCaseFormSchema,
  toApiRequest,
  type OpenCaseFormInput,
  type OpenCaseFormValues,
} from './openCaseForm';
import { Input, Select } from '@/components/ui/input';
import { InlineError } from '@/components/InlineError';
import { AnnuityPurchaseFields } from '@/features/annuities/AnnuityPurchaseFields';
import { useAnnuityTerms } from '@/features/annuities/useAnnuityTerms';
import { addYears, formatDate } from '@/lib/dates';
import { getFuneralTerms } from '@/api/funeral';
import type { FuneralTermsView } from '@/api/types';
import { FuneralLivesFields } from './FuneralLivesFields';
import { getUnitLinkedTerms } from '@/api/unitlinked';
import type { UnitLinkedTermsView } from '@/api/types';
import { UnitLinkedChoiceFields } from './UnitLinkedChoiceFields';

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
    getValues,
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
  const applicantPartyId = watch('applicantPartyId');
  const agentOfRecordId = watch('agentOfRecordId');

  // Who will actually earn on the policy this case issues. The server binds the introducing
  // agent at issuance, so this reads the same fact rather than keeping a second copy of it.
  const loadParty = usePartyStore((s) => s.loadParty);
  const applicant = usePartyStore(selectParty(applicantPartyId));
  useEffect(() => {
    if (applicantPartyId) void loadParty(applicantPartyId);
  }, [applicantPartyId, loadParty]);
  const introducingAgentPartyId = applicant.data?.registeredByPartyId ?? null;

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

  // An annuity purchase (product step 5): the version's forms, and the choice the applicant makes.
  // A deferred annuity (D2) is told apart by its vesting terms: it records a retirement age instead.
  const isAnnuityProduct = selectedProduct?.category === 'ANNUITY';
  const productVersionId = watch('productVersionId');
  const annuityTerms = useAnnuityTerms(isAnnuityProduct ? productId : '', isAnnuityProduct ? productVersionId : '');
  const vestingTerms = annuityTerms?.terms?.vesting ?? null;
  const isDeferredAnnuity = isAnnuityProduct && vestingTerms != null;
  const isAnnuity = isAnnuityProduct && !isDeferredAnnuity;
  const annuityFormCode = watch('annuityFormCode');
  const annuityFormIsJoint =
    annuityTerms?.terms?.forms.find((f) => f.formCode === annuityFormCode)?.joint ?? false;
  useEffect(() => {
    setValue('isAnnuity', isAnnuity);
    setValue('isDeferredAnnuity', isDeferredAnnuity);
    setValue('annuityJointRequired', isAnnuity && annuityFormIsJoint);
  }, [isAnnuity, isDeferredAnnuity, annuityFormIsJoint, setValue]);

  // The vesting date the retirement age gives: the life assured's date of birth plus that age.
  const lifeAssuredPartyId = watch('lifeAssuredPartyId');
  const lifeAssured = usePartyStore(selectParty(lifeAssuredPartyId));
  useEffect(() => {
    if (lifeAssuredPartyId) void loadParty(lifeAssuredPartyId);
  }, [lifeAssuredPartyId, loadParty]);
  const annuitantBorn = (lifeAssuredPartyId ? lifeAssured.data : applicant.data)?.dateOfBirth ?? null;

  // A funeral plan (family funeral cover): the life assured -- else the applicant -- is the main member.
  const isFuneral = selectedProduct?.category === 'FUNERAL';
  const [funeralTerms, setFuneralTerms] = useState<FuneralTermsView | null>(null);
  useEffect(() => {
    if (!isFuneral || !productId || !productVersionId) return undefined;
    let live = true;
    getFuneralTerms(productId, productVersionId).then((t) => { if (live) setFuneralTerms(t); }, () => undefined);
    return () => { live = false; };
  }, [isFuneral, productId, productVersionId]);
  useEffect(() => {
    setValue('isFuneral', isFuneral);
    // A funeral plan is paid monthly, quarterly or annually (FuneralQuoter): a single premium chosen for another
    // product is cleared rather than left selected behind an option the list no longer shows.
    if (isFuneral && getValues('premiumFrequency') === 'SINGLE') setValue('premiumFrequency', '');
  }, [isFuneral, setValue, getValues]);

  // A unit-linked case (product step 6): the version's funds, premium minimums and sum-assured multiples.
  // One split row per offered fund, set when the terms arrive.
  const isUnitLinked = selectedProduct?.category === 'UNIT_LINKED';
  const [unitLinkedTerms, setUnitLinkedTerms] = useState<UnitLinkedTermsView | null>(null);
  useEffect(() => {
    if (!isUnitLinked || !productId || !productVersionId) return undefined;
    let live = true;
    getUnitLinkedTerms(productId, productVersionId).then((t) => {
      if (!live) return;
      setUnitLinkedTerms(t);
      setValue('ulSplit', (t?.fundCodes ?? []).map((fundCode) => ({ fundCode, percent: '' })));
    }, () => undefined);
    return () => { live = false; };
  }, [isUnitLinked, productId, productVersionId, setValue]);
  useEffect(() => {
    setValue('isUnitLinked', isUnitLinked);
  }, [isUnitLinked, setValue]);
  const mainMemberParty = (lifeAssuredPartyId ? lifeAssured.data : applicant.data) ?? null;
  const mainMember = mainMemberParty
    ? { name: mainMemberParty.displayName ?? 'Main member', dateOfBirth: mainMemberParty.dateOfBirth ?? null }
    : null;
  const retirementAge = watch('retirementAge');
  const vestsOn = annuitantBorn && /^\d+$/.test(retirementAge) ? addYears(annuitantBorn, Number(retirementAge)) : null;

  // A savings product that keeps an account (SAV, PEN, DANN): the account charges its policy will carry (2026-10-09).
  const keepsAccount = ['SAV', 'PEN', 'DANN'].includes(selectedProduct?.portfolioCode ?? '');
  const [chargeIds, setChargeIds] = useState<string[]>([]);

  async function onSubmit(values: OpenCaseFormValues) {
    await openCase({ ...toApiRequest(values), ...(keepsAccount && chargeIds.length > 0 ? { accountChargeIds: chargeIds } : {}) });
    const result = useUnderwritingStore.getState().opening;
    if (result.status === 'success' && result.data?.caseId) {
      navigate(`../${result.data.caseId}`, { relative: 'path' });
    }
  }

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Underwriting', to: '/staff/underwriting' }]}
        title="Open an underwriting case"
        description="Browsable afterward from the Underwriting queue — but a policy later issued from it never re-exposes this case's id."
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
              {/* Single-life products only. A group or credit-life product proposed here was
                  issued as one policy covering nobody; the server now refuses it, and those
                  are set up from Group schemes / Credit-life schemes instead. */}
              {(products.data ?? []).filter(isSingleLifeProduct).map((p) => (
                <option key={p.productId} value={p.productId}>
                  {p.productName} ({p.productCode})
                </option>
              ))}
            </Select>
          )}
          {productId && isInitialLoad(snapshot) && (
            <p className="mt-1 text-xs text-muted-foreground">Resolving product version…</p>
          )}
          {/* A product with no version in force today -- its only version retired, or not yet
              effective -- cannot be proposed. Said here, in the server's words: the version id is
              a hidden field, so without this "Open case" refused silently, with nothing on screen
              saying why. */}
          {productId && snapshot.status === 'error' && snapshot.error && (
            <div className="mt-1">
              <InlineError error={snapshot.error} />
            </div>
          )}
          {productId && snapshot.status !== 'error' && errors.productVersionId?.message && (
            <p role="alert" className="mt-1 text-xs text-status-danger-fg">
              This product&apos;s version has not resolved yet. Wait a moment, or choose the product again.
            </p>
          )}
        </FormField>

        <div className="grid grid-cols-[1fr_auto] gap-2">
          <FormField
            label={isDeferredAnnuity ? 'Contribution per payment' : isAnnuity ? 'Purchase price'
              : isFuneral ? "Sum assured (the main member's benefit on the plan)" : 'Sum assured'}
            error={errors.sumAssuredAmount?.message}
          >
            <Input
              placeholder="1500000.00"
              readOnly={isFuneral}
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

        {/*
          THE INTRODUCING AGENT WINS, so this field is replaced rather than silently overridden.

          The agent who registered a client is bound as the agent of record when the policy is
          issued — a business rule, not a default — so anything typed here is ignored for a
          client who has one. A field that accepts a value and then discards it is worse than no
          field at all: whoever opened the case believes they attributed the sale, and did not.

          Named rather than merely suppressed. A decision that accepts this case issues a real
          policy and pays a real person; who that is belongs on screen before the case is opened.
        */}
        {introducingAgentPartyId ? (
          <FormField label="Agent of record">
            <div className="rounded-md border border-border bg-surface-muted px-3 py-2 text-xs">
              <PartyName partyId={introducingAgentPartyId} />
            </div>
            <p className="mt-1 text-xs text-subtle-foreground">
              The agent who introduced this client. Commission on the policy this case issues
              accrues to them, and that is not editable here.
            </p>
          </FormField>
        ) : (
          <FormField label="Agent of record (optional)" error={errors.agentOfRecordId?.message}>
            {/*
              A picker, not a uuid box — see IssuePolicyPage's note. The case carries this value
              all the way to issuance, so a uuid mistyped here surfaced as a policy attributed to
              nobody, one step removed from the form that caused it.
            */}
            <AgentPicker
              value={agentOfRecordId || null}
              onChange={(agentId) =>
                setValue('agentOfRecordId', agentId ?? '', { shouldValidate: true })
              }
            />
            <p className="mt-1 text-xs text-subtle-foreground">
              No agent introduced this client, so the sale is attributed here. Leave it empty for
              a direct sale — a policy issued with no agent of record accrues no commission.
            </p>
          </FormField>
        )}

        {/* Where the business came from. Last and grouped: all three are optional, and the
            risk — who, what product, how much — is what the form is actually for. */}
        <fieldset className="border-t border-border pt-3">
          <legend className="pr-2 text-xs font-medium tracking-wide text-subtle-foreground uppercase">
            Source
          </legend>

          <div className="grid grid-cols-2 gap-3">
            <FormField label="Branch" hint="Blank takes the agent's branch, or yours.">
              <Controller
                control={control}
                name="branchCode"
                render={({ field }) => (
                  <BranchSelect value={field.value} onChange={field.onChange} allowNone noneLabel="Default" />
                )}
              />
            </FormField>

            <FormField label="Sales channel" hint="Blank takes the agent's channel, or Direct.">
              <Select {...register('salesChannel')}>
                <option value="">Default</option>
                {Object.keys(CHANNEL_LABEL).map((c) => (
                  <option key={c} value={c}>
                    {CHANNEL_LABEL[c]}
                  </option>
                ))}
              </Select>
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

          {isAnnuity && annuityTerms?.error != null && (
            <InlineError error={annuityTerms.error as Parameters<typeof InlineError>[0]['error']} />
          )}
          {isAnnuity && annuityTerms?.terms && (
            <AnnuityPurchaseFields
              terms={annuityTerms.terms}
              productVersionId={productVersionId}
              formCode={annuityFormCode}
              frequency={watch('annuityFrequency')}
              jointLifePartyId={watch('annuityJointLifePartyId')}
              annuitantPartyId={watch('lifeAssuredPartyId') || applicantPartyId}
              purchasePrice={watch('sumAssuredAmount')}
              register={register}
              control={control}
              errors={errors}
            />
          )}

          {/* A deferred annuity (D2): saves to the retirement age; its form is chosen when it vests. */}
          {isDeferredAnnuity && vestingTerms && (
            <div className="grid grid-cols-2 gap-4">
              <FormField label="Retirement age" error={errors.retirementAge?.message}>
                <Input inputMode="numeric" placeholder={String(vestingTerms.minVestingAge)} {...register('retirementAge')} />
                <p className="mt-1 text-xs text-subtle-foreground">
                  Between {vestingTerms.minVestingAge} and {vestingTerms.maxVestingAge}.{' '}
                  {vestsOn
                    ? `Vests on ${formatDate(vestsOn)}`
                    : annuitantBorn
                      ? ''
                      : "Record the applicant's date of birth to see the vesting date"}
                </p>
              </FormField>
            </div>
          )}

          {/* A funeral plan: the plan, the family, and the server's quote of it as it is typed. */}
          {isFuneral && funeralTerms && (
            <FuneralLivesFields terms={funeralTerms} productId={productId} productVersionId={productVersionId}
              mainMember={mainMember} register={register} control={control} errors={errors} setValue={setValue} />
          )}

          {keepsAccount && (
            <div className="rounded-md border border-border p-3">
              <AccountChargePicker value={chargeIds} onChange={setChargeIds} legend="Account charges for this policy" />
            </div>
          )}

          {/* A unit-linked case: the premium, the fund split, and the version's bounds as they are typed. */}
          {isUnitLinked && unitLinkedTerms && (
            <UnitLinkedChoiceFields terms={unitLinkedTerms} register={register} control={control} errors={errors}
              currency={watch('sumAssuredCurrency') || 'TZS'} />
          )}

          {/* An annuity has no term and no premium frequency: one single premium, paid for life. */}
          {!isAnnuity && (
          <div className="grid grid-cols-2 gap-4">
            {/* A funeral plan renews yearly: no term. */}
            {!isDeferredAnnuity && !isFuneral && (
              <>
                <FormField
                  label={isUnitLinked ? 'Term (months; blank for whole of life)' : 'Term (months)'}
                  error={errors.requestedTermMonths?.message}
                >
                  <Input placeholder="120" {...register('requestedTermMonths')} />
                </FormField>

                {/* A unit-linked policy pays its premiums for as long as it runs. */}
                {!isUnitLinked && (
                  <FormField
                    label="Premium-paying term (months)"
                    error={errors.premiumPayingTermMonths?.message}
                  >
                    <Input
                      placeholder={watch('premiumFrequency') === 'SINGLE' ? 'Blank — paid once' : 'Same as the term'}
                      {...register('premiumPayingTermMonths')}
                    />
                  </FormField>
                )}
              </>
            )}

            <FormField
              label={isDeferredAnnuity ? 'Contribution frequency' : 'Premium frequency'}
              error={errors.premiumFrequency?.message}
            >
              <Select {...register('premiumFrequency')}>
                {/* Blank first and selected by default: a frequency the applicant did not
                    state is not monthly, and it is what the issued policy is billed on. */}
                <option value="">Not stated</option>
                {PREMIUM_FREQUENCIES.filter((f) => !(isFuneral && f === 'SINGLE')).map((f) => (
                  <option key={f} value={f}>
                    {PREMIUM_FREQUENCY_LABELS[f]}
                  </option>
                ))}
              </Select>
            </FormField>
          </div>
          )}

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
          <InlineError error={opening.error} />
        )}

        <div className="flex items-center gap-2">
          <Button type="submit" variant="primary" pending={opening.status === 'loading'}>
            Open case
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
