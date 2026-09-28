import { Plus } from 'lucide-react';
import { useEffect } from 'react';
import { useFieldArray, useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate } from 'react-router-dom';
import { isSingleLifeProduct, ISSUANCE_BASES, PREMIUM_FREQUENCIES } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { DatePicker } from '@/components/DatePicker';
import { PartyPicker } from '@/components/PartyPicker';
import { PartyName } from '@/components/PartyName';
import { AgentPicker } from '@/components/AgentPicker';
import { UnderwritingCasePicker } from '@/components/UnderwritingCasePicker';
import { listCaseBeneficiaries } from '@/api/underwriting';
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
import {
  selectAgent,
  selectAgentForParty,
  selectApplicablePlan,
  useDistributionStore,
} from '@/store/distributionStore';
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
import { InlineError } from '@/components/InlineError';

/**
 * `POST /policies/manual-issue` -- the staff exception path.
 *
 * It declares no `Idempotency-Key`, unlike every other mutation in this console, so the
 * submit button disabling while in flight still matters. But it is no longer the only
 * guard: `issuePolicy` now refuses a case that already has a policy and answers with the
 * existing policy number, so a double-click, a retry, or a second operator finishing the
 * same job gets a 409 rather than a second contract.
 *
 * That backstop only works because this form names a REAL underwriting case. It used to
 * synthesize one per submission, which made every issuance look like a first issuance --
 * see `policyIssueForm.ts` for the full account.
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
  const issuanceBasis = watch('issuanceBasis');

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

  // Who actually earns on this policy. Derived rather than stored: the server binds the
  // introducing agent at issuance, so this is a read of the same fact, not a second copy of it.
  const introducingAgentPartyId = policyholder.data?.registeredByPartyId ?? null;

  // The introducing agent's own record, resolved from their party id.
  //
  // Without this the gates below saw an agent ONLY when one was typed into the form -- so for
  // a client an agent introduced, which is precisely when the binding applies, the licence
  // gates evaluated against `null` and silently did not run. The console was checking the
  // licence of the agent it was NOT going to attribute, and skipping the one it was.
  const loadAgentForParty = useDistributionStore((s) => s.loadAgentForParty);
  const introducingAgent = useDistributionStore(selectAgentForParty(introducingAgentPartyId));
  useEffect(() => {
    if (introducingAgentPartyId) void loadAgentForParty(introducingAgentPartyId);
  }, [introducingAgentPartyId, loadAgentForParty]);

  // The agent the PLATFORM will use, in the platform's own precedence: an introducing agent
  // binds and wins; otherwise whatever was attributed by hand.
  const effectiveAgent = introducingAgent ?? agent.data ?? null;

  const snapshot = useProductStore(selectProductSnapshot(productId));

  // Does a commission plan cover this agent on this product? A 404 is the real answer "none
  // applies", not a failure -- see CommissionPlanPanel, which treats it the same way.
  const loadApplicablePlan = useDistributionStore((s) => s.loadApplicablePlan);
  const planResource = useDistributionStore(
    selectApplicablePlan(effectiveAgent?.agentId ?? '', productId ?? ''),
  );
  useEffect(() => {
    if (effectiveAgent?.agentId && productId) {
      void loadApplicablePlan(effectiveAgent.agentId, productId);
    }
  }, [effectiveAgent?.agentId, productId, loadApplicablePlan]);
  const commissionPlan =
    planResource.status === 'success'
      ? (planResource.data ?? null)
      : planResource.status === 'error' && planResource.error?.kind === 'notFound'
        ? null
        : undefined;

  const gates = issueGates({
    snapshot: snapshot.data ?? null,
    policyholder: policyholder.data ?? null,
    agent: effectiveAgent,
    commissionPlan,
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
      <PageHeader
        breadcrumb={[{ label: 'Policies', to: '/staff/policies' }]}
        title="Issue a policy"
        description="The staff exception path -- outside the normal underwriting-decision pipeline."
      />

      <form className="max-w-xl space-y-4 px-6 pb-8" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
        {/*
          First, because everything below is a review of what it says.

          This field used to not exist: `toApiRequest` invented a `crypto.randomUUID()` for
          every submission, so each manually issued policy pointed at a case that was never
          opened. That defeated the server's one-policy-per-case check outright -- random ids
          never collide -- and left claims contestability resolving to nothing on exactly the
          policies a human had touched.

          Selecting a case prefills the fields it knows, which stay editable. That is the
          difference between this screen being a review step and being a second, parallel
          data-entry form that happens to disagree with the case.
        */}
        <FormField
          label="Underwriting case this policy is issued from"
          error={errors.underwritingCaseId?.message}
        >
          <Controller
            control={control}
            name="underwritingCaseId"
            render={({ field }) => (
              <UnderwritingCasePicker
                value={field.value || null}
                onChange={(caseId, decidedCase) => {
                  field.onChange(caseId ?? '');
                  if (!decidedCase) return;
                  // Everything the proposal actually states. It still holds no premium -- the
                  // insurer works that out rather than the applicant stating it -- so that one
                  // stays for the operator.
                  if (decidedCase.applicantPartyId) {
                    setValue('policyholderPartyId', decidedCase.applicantPartyId);
                  }
                  if (decidedCase.lifeAssuredPartyId
                      && decidedCase.lifeAssuredPartyId !== decidedCase.applicantPartyId) {
                    setValue('lifeAssuredPartyId', decidedCase.lifeAssuredPartyId);
                  }
                  if (decidedCase.productId) setValue('productId', decidedCase.productId);
                  if (decidedCase.proposedCommencementDate) {
                    setValue('commencementDate', decidedCase.proposedCommencementDate);
                  }
                  if (decidedCase.requestedTermMonths != null) {
                    setValue('policyTermMonths', String(decidedCase.requestedTermMonths));
                  }
                  if (decidedCase.premiumPayingTermMonths != null) {
                    setValue('premiumPayingTermMonths', String(decidedCase.premiumPayingTermMonths));
                  }
                  if (decidedCase.premiumFrequency) {
                    setValue('premiumFrequency', decidedCase.premiumFrequency);
                  }

                  /*
                    The nominations, and this one is not a convenience.

                    This form SENDS its own beneficiary list, so a manual issuance against a
                    case that named beneficiaries would otherwise drop them silently -- by the
                    exact path that exists to honour a proposal when the automatic one could
                    not. They come from their own sub-resource rather than off the case,
                    because a case list carries none: see listCaseBeneficiaries.

                    A failure here is swallowed deliberately. The rows stay empty and the
                    operator can type them, which is strictly better than refusing to let them
                    issue at all because a secondary read failed.
                  */
                  if (caseId) {
                    void listCaseBeneficiaries(caseId)
                      .then((nominations) => {
                        if (nominations.length === 0) return;
                        setValue(
                          'beneficiaries',
                          nominations.map((n) => ({
                            type: n.type ?? 'FREEFORM',
                            partyId: n.partyId ?? '',
                            freeformDesignee: n.freeformDesignee ?? '',
                            sharePercent: n.sharePercent ?? 0,
                            revocable: n.revocable ?? true,
                          })),
                        );
                      })
                      .catch(() => {});
                  }
                }}
              />
            )}
          />
        </FormField>

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
          <legend className="pr-2 text-xs font-medium tracking-wide text-subtle-foreground uppercase">
            Term
          </legend>
          <p className="mb-2.5 text-xs text-muted-foreground">
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
              <p className="text-xs text-muted-foreground">Matures</p>
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

        {/*
          THE INTRODUCING AGENT WINS, so this field is disabled rather than silently overridden.

          The agent who registered a client is bound as the agent of record at issuance — a
          business rule, not a default — so whatever is typed here is ignored for a client who
          has one. A field that accepts a value and then discards it is worse than no field: the
          operator believes they have attributed the sale and has not.

          Shown, not hidden, and naming the agent: "who earns on this policy" is a question the
          person issuing it is entitled to see answered before they issue it.
        */}
        {introducingAgentPartyId ? (
          <FormField label="Agent of record">
            <div className="rounded-md border border-border bg-surface-muted px-3 py-2 text-xs">
              <PartyName partyId={introducingAgentPartyId} />
            </div>
            <p className="mt-1 text-xs text-subtle-foreground">
              The agent who introduced this client. Commission on this policy accrues to them, and
              that is not editable here — an attribution an issue form could rewrite is a
              commission an issue form could reassign.
            </p>
          </FormField>
        ) : (
          <FormField label="Agent of record (optional)" error={errors.agentOfRecordId?.message}>
            {/*
              A picker, not a uuid box. Nobody knows an agent's uuid, so one got copied from
              somewhere else -- and a wrong one was accepted in silence: the policy was
              attributed to nobody, and only a server log said so. The backend now refuses an
              unknown agent outright; searching by name is what stops one being entered.
            */}
            <AgentPicker
              value={agentOfRecordId || null}
              onChange={(agentId) =>
                setValue('agentOfRecordId', agentId ?? '', { shouldValidate: true })
              }
              placeholder="Search agents by name or licence…"
            />
            <p className="mt-1 text-xs text-subtle-foreground">
              No agent introduced this client, so the sale is attributed here. Leave it empty for
              a direct sale, which accrues no commission.
            </p>
          </FormField>
        )}

        <GatePanel gates={gates} title="Before issuing" />

        {/*
          The basis, above the free-text reason and not merged into it. The enum is what a
          report groups by; the sentence below is what a person reads.

          The consequence line is the point of putting this on screen at all. Three of the five
          values put the contract on risk before anybody has paid for it, and nothing else on
          this form says so -- an operator picking from a bare dropdown would be choosing
          between "covered now" and "covered when they pay" without being told that is the
          choice they are making.
        */}
        <FormField label="Why is this being issued by hand?" error={errors.issuanceBasis?.message}>
          <Select {...register('issuanceBasis')}>
            <option value="">Select a basis…</option>
            {ISSUANCE_BASES.map((basis) => (
              <option key={basis.value} value={basis.value}>
                {basis.label}
              </option>
            ))}
          </Select>
          {issuanceBasis !== '' && (
            <p className="mt-1 text-xs text-muted-foreground">
              {ISSUANCE_BASES.find((b) => b.value === issuanceBasis)?.startsCoverImmediately
                ? 'Cover starts immediately — this contract is already in force elsewhere.'
                : 'Cover starts when the first premium clears.'}
            </p>
          )}
        </FormField>

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
            <p className="mt-1 text-xs text-status-warning-fg">
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
          <InlineError error={issuing.error} />
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
