import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { acceptQuotation } from '@/api/agentQuotations';
import { ConfirmAct } from '@/components/ConfirmAct';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PartyPicker } from '@/components/PartyPicker';
import { Input, Select } from '@/components/ui/input';
import { isSingleLifeProduct, PREMIUM_FREQUENCIES, PREMIUM_FREQUENCY_LABELS } from '@/api/types';
import type { FuneralTermsView } from '@/api/types';
import { getFuneralTerms } from '@/api/funeral';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectProductSnapshot, useProductStore } from '@/store/productStore';
import { selectParty, usePartyStore } from '@/store/partyStore';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { formatMonths } from '@/lib/dates';
import { TypeTab } from '@/features/party/OnboardCustomerPage';
import {
  blankRegisterIndividualForm,
  type RegisterIndividualFormValues,
} from '@/features/party/registerIndividualForm';
import { FuneralLivesFields } from './FuneralLivesFields';
import {
  blankOpenCaseForm,
  openCaseFormSchema,
  type OpenCaseFormInput,
  type OpenCaseFormValues,
} from './openCaseForm';
import { QuotationPreview } from './QuotationPreview';
import { pricingBasis, quotesHere, type ProspectInputs } from './pricingBasis';
import { quotationRequest } from './quotationRequest';

type Who = 'PROSPECT' | 'CLIENT';

/**
 * The agents' quotation calculator (2026-10-20): what a product would cost a person and what it would pay them,
 * before anybody is registered.
 *
 * <p><b>Nothing is stored.</b> Every figure on this page is a read: the same quote endpoints the staff open-case
 * screen prices with, and the same quotation PDF, which takes the prospect's details as INPUTS. Changing a field,
 * reading the quote and downloading the paper write nothing to the database.
 *
 * <p>The plan's fields are the open-case form's own schema, so the funeral family fields and the quotation panel are
 * the components staff already use rather than a second copy; the person's fields carry registration's labels, so the
 * same control means the same thing on both screens.
 */
export function QuotationCalculatorPage() {
  const [who, setWho] = useState<Who>('PROSPECT');

  const products = useProductStore((s) => s.list);
  const loadProducts = useProductStore((s) => s.loadList);
  const loadSnapshot = useProductStore((s) => s.loadSnapshot);

  useEffect(() => {
    void loadProducts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // The plan: the open-case form's schema, never submitted here.
  const { register, watch, setValue, control, formState: { errors } } = useForm<
    OpenCaseFormInput,
    unknown,
    OpenCaseFormValues
  >({ ...VALIDATE_ON_TOUCH, resolver: zodResolver(openCaseFormSchema), defaultValues: blankOpenCaseForm() });

  // The person, when they are not registered yet: registration's own fields, so onboarding can take them over.
  const person = useForm<RegisterIndividualFormValues>({ defaultValues: blankRegisterIndividualForm() });

  // eslint-disable-next-line react-hooks/incompatible-library -- see IssuePolicyPage
  const productId = watch('productId');
  const productVersionId = watch('productVersionId');
  const applicantPartyId = watch('applicantPartyId');
  const snapshot = useProductStore(selectProductSnapshot(productId));

  useEffect(() => {
    if (productId) void loadSnapshot(productId);
  }, [productId, loadSnapshot]);

  const offered = (products.data ?? []).filter((p) => isSingleLifeProduct(p) && quotesHere(p.portfolioCode));
  const selectedProduct = offered.find((p) => p.productId === productId);

  useEffect(() => {
    if (snapshot.data?.productVersionId) setValue('productVersionId', snapshot.data.productVersionId);
    if (selectedProduct?.defaultCurrency) setValue('sumAssuredCurrency', selectedProduct.defaultCurrency);
  }, [snapshot.data, selectedProduct, setValue]);

  // An existing client is read from their record, exactly as the staff screen reads an applicant.
  const loadParty = usePartyStore((s) => s.loadParty);
  const client = usePartyStore(selectParty(who === 'CLIENT' ? applicantPartyId : ''));
  useEffect(() => {
    if (who === 'CLIENT' && applicantPartyId) void loadParty(applicantPartyId);
  }, [who, applicantPartyId, loadParty]);

  const p = person.watch();
  const prospect: ProspectInputs = {
    fullName: p.fullName,
    phoneNumber: p.phoneNumber,
    dateOfBirth: p.dateOfBirth,
    sex: p.sex,
    smokerStatus: p.smokerStatus,
    occupationClass: p.occupationClass,
  };
  const rating =
    who === 'CLIENT'
      ? {
          name: client.data?.displayName ?? '',
          dateOfBirth: client.data?.dateOfBirth,
          sex: client.data?.sex,
          smokerStatus: client.data?.smokerStatus,
          occupationClass: client.data?.occupationClass,
        }
      : {
          name: prospect.fullName,
          dateOfBirth: prospect.dateOfBirth || null,
          sex: prospect.sex || null,
          smokerStatus: prospect.smokerStatus || null,
          occupationClass: prospect.occupationClass || null,
        };

  // A funeral plan: the person quoted is the main member.
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
  }, [isFuneral, setValue]);
  const mainMember = rating.name ? { name: rating.name, dateOfBirth: rating.dateOfBirth ?? null } : null;

  const singleRefusedByTerm = Number(watch('requestedTermMonths')) > 12;

  const funeral = isFuneral
    ? {
        planCode: watch('funeralPlanCode'),
        mainMember,
        dependants: (watch('funeralDependants') ?? []).map((d) => ({
          role: d.role,
          fullName: d.fullName,
          dateOfBirth: d.dateOfBirth,
          student: d.student,
        })),
      }
    : undefined;
  const inputs = {
    productId,
    sumAssuredAmount: watch('sumAssuredAmount'),
    sumAssuredCurrency: watch('sumAssuredCurrency'),
    frequency: watch('premiumFrequency'),
    policyTermMonths: watch('requestedTermMonths'),
    dateOfBirth: rating.dateOfBirth,
    sex: rating.sex,
    smokerStatus: rating.smokerStatus,
    occupationClass: rating.occupationClass,
  };
  // Exactly what the PDF is asked for with: the inputs, never the figures on screen.
  const request = selectedProduct
    ? quotationRequest(pricingBasis(selectedProduct.portfolioCode), {
        inputs,
        premiumPayingTermMonths: watch('premiumPayingTermMonths'),
        commencementDate: watch('proposedCommencementDate'),
        productVersionId,
        applicantPartyId: who === 'CLIENT' ? applicantPartyId : '',
        prospect: who === 'PROSPECT' ? prospect : undefined,
        funeral,
      })
    : null;

  // Proceed, then confirm: nothing is sent until the second click, and Cancel sends nothing at all.
  const navigate = useNavigate();
  const [step, setStep] = useState<'quote' | 'confirm'>('quote');
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<ApiError | null>(null);
  async function confirm() {
    if (!request) return;
    setSaving(true);
    setSaveError(null);
    try {
      const saved = await acceptQuotation(request);
      navigate(
        who === 'CLIENT' ? `../quotation/${saved.quotationId}` : `../customers/new?quotation=${saved.quotationId}`,
        { relative: 'path' },
      );
    } catch (e) {
      setSaveError(toApiError(e));
      setSaving(false);
    }
  }

  // A product sold on ONE term fills that term in itself, and a bounded one says its bounds -- as the staff
  // open-case form does, so the calculator never invites a term the product will refuse.
  const termBounds = snapshot.data?.eligibility ?? null;
  const fixedTermMonths =
    termBounds?.minTermMonths != null && termBounds.minTermMonths === termBounds.maxTermMonths
      ? termBounds.minTermMonths
      : null;
  useEffect(() => {
    if (fixedTermMonths != null) setValue('requestedTermMonths', String(fixedTermMonths));
  }, [fixedTermMonths, setValue]);
  const termHint =
    fixedTermMonths != null
      ? `Sold on one term only: ${formatMonths(fixedTermMonths)}.`
      : termBounds?.minTermMonths != null && termBounds.maxTermMonths != null
        ? `This product sells terms from ${formatMonths(termBounds.minTermMonths)} to ${formatMonths(termBounds.maxTermMonths)}.`
        : undefined;

  return (
    <>
      <PageHeader
        title="Quotation calculator"
        description="What a product costs and what it pays, before anybody is registered. Nothing is saved."
      />

      <div className="max-w-xl space-y-4 px-6 pb-8">
        <div className="inline-flex rounded-md border border-border p-0.5">
          <TypeTab label="New person" active={who === 'PROSPECT'} onClick={() => setWho('PROSPECT')} />
          <TypeTab label="Existing client" active={who === 'CLIENT'} onClick={() => setWho('CLIENT')} />
        </div>

        {who === 'PROSPECT' ? (
          <fieldset className="space-y-4">
            <legend className="text-sm font-medium">The person</legend>
            <FormField label="Full name">
              <Input placeholder="Amina Hassan" {...person.register('fullName')} />
            </FormField>
            <div className="grid grid-cols-2 gap-3">
              <FormField label="Date of birth">
                <Controller
                  control={person.control}
                  name="dateOfBirth"
                  render={({ field }) => (
                    <DatePicker
                      value={field.value || null}
                      onChange={(iso) => field.onChange(iso ?? '')}
                      disabled={{ after: new Date() }}
                    />
                  )}
                />
              </FormField>
              <FormField label="Phone number (optional)">
                <Input placeholder="+255712345678" {...person.register('phoneNumber')} />
              </FormField>
              <FormField label="Sex">
                <Select {...person.register('sex')}>
                  <option value="">Not recorded</option>
                  <option value="FEMALE">Female</option>
                  <option value="MALE">Male</option>
                </Select>
              </FormField>
              <FormField label="Smoker status">
                <Select {...person.register('smokerStatus')}>
                  <option value="">Not recorded</option>
                  <option value="NON_SMOKER">Non-smoker</option>
                  <option value="SMOKER">Smoker</option>
                  <option value="UNKNOWN">Asked, declined to say</option>
                </Select>
              </FormField>
              <FormField label="Occupation class">
                <Input placeholder="Rating band" {...person.register('occupationClass')} />
              </FormField>
            </div>
          </fieldset>
        ) : (
          <FormField label="Client" hint="Your registered clients. Their details are read from their record.">
            <Controller
              control={control}
              name="applicantPartyId"
              render={({ field }) => (
                <PartyPicker
                  value={field.value || null}
                  onChange={(partyId) => field.onChange(partyId ?? '')}
                  placeholder="Search your clients by name"
                />
              )}
            />
          </FormField>
        )}

        <fieldset className="space-y-4">
          <legend className="text-sm font-medium">The plan</legend>
          <FormField
            label="Product"
            hint="Life, endowment, savings, deposit and funeral products. Annuities, unit-linked and group cover are quoted by the office."
          >
            {isInitialLoad(products) ? (
              <p className="text-xs text-muted-foreground">Loading products…</p>
            ) : products.status === 'error' ? (
              <p className="text-xs text-status-danger-fg">Could not load products.</p>
            ) : (
              <Select {...register('productId')}>
                <option value="">Select a product</option>
                {offered.map((product) => (
                  <option key={product.productId} value={product.productId}>
                    {product.productName} ({product.productCode})
                  </option>
                ))}
              </Select>
            )}
            {productId && isInitialLoad(snapshot) && (
              <p className="mt-1 text-xs text-muted-foreground">Resolving product version…</p>
            )}
            {productId && snapshot.status === 'error' && snapshot.error && (
              <div className="mt-1">
                <InlineError error={snapshot.error} />
              </div>
            )}
          </FormField>

          {isFuneral && funeralTerms && (
            <FuneralLivesFields terms={funeralTerms} mainMember={mainMember} register={register} control={control}
              errors={errors} setValue={setValue} />
          )}

          <div className="grid grid-cols-[1fr_auto] gap-2">
            <FormField
              label={isFuneral ? "Sum assured (the main member's benefit on the plan)" : 'Sum assured or amount'}
            >
              <Input placeholder="1500000.00" readOnly={isFuneral} {...register('sumAssuredAmount')} />
            </FormField>
            <FormField label="Currency">
              <Input className="w-20 uppercase" {...register('sumAssuredCurrency')} />
            </FormField>
          </div>

          <div className="grid grid-cols-2 gap-4">
            {!isFuneral && (
              <>
                <FormField label="Term (months)" hint={termHint}>
                  <Input placeholder="120" readOnly={fixedTermMonths != null} {...register('requestedTermMonths')} />
                </FormField>
                <FormField label="Premium-paying term (months)">
                  <Input
                    placeholder={watch('premiumFrequency') === 'SINGLE' ? 'Blank — paid once' : 'Same as the term'}
                    {...register('premiumPayingTermMonths')}
                  />
                </FormField>
              </>
            )}
            <FormField label="Premium frequency">
              <Select {...register('premiumFrequency')}>
                <option value="">Not stated</option>
                {PREMIUM_FREQUENCIES.filter(
                  (f) => !(isFuneral && f === 'SINGLE') && !(f === 'SINGLE' && singleRefusedByTerm),
                ).map((f) => (
                  <option key={f} value={f}>
                    {PREMIUM_FREQUENCY_LABELS[f]}
                  </option>
                ))}
              </Select>
            </FormField>
            <FormField label="Cover from" hint="Blank means today.">
              <Controller
                control={control}
                name="proposedCommencementDate"
                render={({ field }) => (
                  <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />
                )}
              />
            </FormField>
          </div>
        </fieldset>

        <QuotationPreview
          inputs={inputs}
          premiumPayingTermMonths={watch('premiumPayingTermMonths')}
          commencementDate={watch('proposedCommencementDate')}
          portfolioCode={selectedProduct?.portfolioCode}
          productVersionId={productVersionId}
          applicantPartyId={who === 'CLIENT' ? applicantPartyId : ''}
          prospect={who === 'PROSPECT' ? prospect : undefined}
          funeral={funeral}
        />

        {/* The customer agrees: the one place on this screen that writes anything, and only after a second click. */}
        {step === 'quote' ? (
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" variant="primary" disabled={!request} onClick={() => setStep('confirm')}>
              {who === 'CLIENT' ? 'Proceed to proposal' : 'Proceed to onboarding'}
            </Button>
            <span className="text-xs text-muted-foreground">When the customer agrees to this quotation.</span>
          </div>
        ) : (
          <ConfirmAct
            heading="Has the customer agreed to this quotation?"
            consequence={
              <>
                Saves this quotation for {rating.name || 'the customer'}: {selectedProduct?.productName ?? 'the product'}
                {request?.sumAssured != null ? `, ${request.currency} ${formatAmount(request.sumAssured)}` : ''}
                {request?.frequency ? `, ${PREMIUM_FREQUENCY_LABELS[request.frequency].toLowerCase()}` : ''}.{' '}
                {who === 'CLIENT'
                  ? 'Then you submit it as a proposal.'
                  : 'Then you register the customer, with these details filled in.'}
              </>
            }
            reversal="Cancel on the next screen discards it. A quotation not submitted as a proposal by the end of today is discarded."
            confirmLabel={who === 'CLIENT' ? 'Confirm and start the proposal' : 'Confirm and start onboarding'}
            busy={saving}
            onConfirm={() => void confirm()}
            onCancel={() => {
              setStep('quote');
              setSaveError(null);
            }}
          >
            {saveError && <InlineError error={saveError} />}
          </ConfirmAct>
        )}

        <WhatHappensNext />
      </div>
    </>
  );
}

/** A sum the agent typed, with grouping -- formatting only, never arithmetic. */
function formatAmount(amount: number): string {
  return new Intl.NumberFormat('en-GB', { maximumFractionDigits: 2 }).format(amount);
}

/** The road from this quotation to a policy, in the order it is travelled. */
function WhatHappensNext() {
  return (
    <Panel title="What happens next" subtitle="From this quotation to a policy.">
      <ol className="list-decimal space-y-1.5 px-4 pb-4 pl-8 text-sm">
        <li>Quote, and give the customer the quotation PDF to take away. Nothing is saved.</li>
        <li>When the customer agrees, proceed and confirm: the accepted quotation is saved.</li>
        <li>Register the customer. Their details come from the quotation.</li>
        <li>Submit the proposal. It goes to an underwriter.</li>
        <li>
          The underwriter accepts it, or returns it to you with what to correct. A returned proposal appears under
          Needs your action; correct it and resubmit.
        </li>
        <li>Once accepted, the policy is issued. The final premium is confirmed at acceptance.</li>
      </ol>
    </Panel>
  );
}
