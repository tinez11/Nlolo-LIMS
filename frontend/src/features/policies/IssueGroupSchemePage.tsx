import { zodResolver } from '@hookform/resolvers/zod';
import { Plus, X } from 'lucide-react';
import { useEffect, useMemo } from 'react';
import { Controller, useFieldArray, useForm, useWatch } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { PREMIUM_FREQUENCIES } from '@/api/types';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { PageHeader } from '@/components/PageHeader';
import { PartyPicker } from '@/components/PartyPicker';
import { NoAccess } from '@/components/states';
import { canUnderwriteGroupSchemes, readIdentity } from '@/auth/claims';
import { useAuth } from 'react-oidc-context';
import { Button } from '@/components/ui/button';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useUnderwritingStore } from '@/store/underwritingStore';
import { selectProductSnapshot, useProductStore } from '@/store/productStore';
import { previewBenefit, type SchemeBasis } from './groupBenefitPreview';
import {
  blankGradeRow,
  blankGroupSchemeIssueForm,
  blankMemberRow,
  groupSchemeIssueFormSchema,
  toProposeCaseRequest,
  type GroupSchemeIssueFormValues,
} from './groupSchemeIssueForm';
import { Input, Select } from '@/components/ui/input';

/**
 * `POST /group-schemes` — the master policy, the scheme and its opening schedule
 * in one submission.
 *
 * **There is no sum assured field, and that is the point.** A scheme's sum
 * assured is the total of what its members are covered for, derived server-side
 * from the schedule below; the running total shown at the foot of the schedule is
 * this page's own preview of it, not an input. That is also why the schedule
 * cannot be left empty: a contract insuring nobody for nothing is not a policy,
 * and the backend refuses one.
 *
 * Like manual issue, this endpoint declares no `Idempotency-Key`. A second
 * identical submission genuinely creates a second scheme, so disabling the button
 * while in flight is the only guard against a double-click — there is no
 * server-side backstop.
 */
export function IssueGroupSchemePage() {
  // Setting a scheme up is an underwriting act: it accepts lives, fixes the free cover
  // limit and the premium, and the contract is on risk the moment it is created. The
  // server enforces it (hasRole(UNDERWRITER) on POST /group-schemes); this keeps the
  // console from offering a form that can only end in a 403.
  const canUnderwrite = canUnderwriteGroupSchemes(readIdentity(useAuth().user?.access_token));
  const navigate = useNavigate();

  const openGroupCase = useUnderwritingStore((s) => s.openGroupCase);
  const resetOpenCase = useUnderwritingStore((s) => s.resetOpenCase);
  const issuing = useUnderwritingStore((s) => s.opening);

  const products = useProductStore((s) => s.list);
  const loadProducts = useProductStore((s) => s.loadList);
  const loadSnapshot = useProductStore((s) => s.loadSnapshot);

  // `issuing` is a single slot outliving this page's mount, so a previous
  // visit's rejection would otherwise greet the next one. Same discipline as
  // IssuePolicyPage.
  useEffect(() => {
    resetOpenCase();
    void loadProducts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const {
    register,
    handleSubmit,
    control,
    setValue,
    formState: { errors },
  } = useForm<GroupSchemeIssueFormValues>({
    resolver: zodResolver(groupSchemeIssueFormSchema()),
    defaultValues: blankGroupSchemeIssueForm(),
  });

  const grades = useFieldArray({ control, name: 'grades' });
  const schedule = useFieldArray({ control, name: 'openingSchedule' });

  const benefitBasis = useWatch({ control, name: 'benefitBasis' });
  const productId = useWatch({ control, name: 'productId' });
  const currency = useWatch({ control, name: 'currency' });
  const flatBenefitAmount = useWatch({ control, name: 'flatBenefitAmount' });
  const salaryMultiple = useWatch({ control, name: 'salaryMultiple' });
  const fclAmount = useWatch({ control, name: 'fclAmount' });
  const gradeRows = useWatch({ control, name: 'grades' });
  const scheduleRows = useWatch({ control, name: 'openingSchedule' });

  // Only GROUP_LIFE products can carry a scheme -- the backend refuses anything
  // else with a 409. Filtering here rather than letting the user pick a term-life
  // product and be told afterwards.
  const groupProducts = useMemo(
    () => (products.data ?? []).filter((p) => p.category === 'GROUP_LIFE'),
    [products.data],
  );

  const snapshot = useProductStore(selectProductSnapshot(productId));
  useEffect(() => {
    if (productId) void loadSnapshot(productId);
  }, [productId, loadSnapshot]);

  // The request carries the VERSION, not the product: a scheme is issued against
  // the terms in force, and resolving that here keeps it out of the user's hands.
  const resolvedVersionId = snapshot.data?.productVersionId ?? '';
  useEffect(() => {
    setValue('productVersionId', resolvedVersionId);
  }, [resolvedVersionId, setValue]);

  const basis: SchemeBasis = {
    benefitBasis,
    currency: currency || 'TZS',
    flatBenefitAmount: flatBenefitAmount || null,
    salaryMultiple: salaryMultiple ? Number(salaryMultiple) : null,
    fclAmount: fclAmount || null,
    gradeBenefits: Object.fromEntries(
      (gradeRows ?? [])
        .filter((g) => g.gradeCode && g.benefitAmount)
        .map((g) => [g.gradeCode, g.benefitAmount]),
    ),
  };

  // What the server will derive, shown while the schedule is being built. Rows
  // that cannot yet be valued contribute nothing rather than a zero -- the total
  // says how many of them there are instead of quietly understating itself.
  const previews = (scheduleRows ?? []).map((row) =>
    previewBenefit(basis, { salaryAmount: row.salaryAmount, gradeCode: row.gradeCode }),
  );
  const valued = previews.filter((p): p is NonNullable<typeof p> => p !== null);
  const unpriced = previews.length - valued.length;
  const overLimit = valued.filter((p) => p.status === 'EVIDENCE_REQUIRED').length;
  const runningTotal = sumAmounts(valued.map((p) => p.covered.amount));

  /**
   * PROPOSES the scheme rather than issuing it.
   *
   * <p>This form used to POST /group-schemes, which created the policy, the scheme and every
   * member in one call, on risk on return -- no case, no assessment, no decision, while
   * individual business had all three. It now opens an underwriting case, and the decision
   * issues the scheme as an offer the employer accepts by paying.
   *
   * <p>So it lands on the CASE, not on a scheme: there is no scheme yet, and there will not
   * be one until somebody decides there should be.
   */
  async function onSubmit(values: GroupSchemeIssueFormValues) {
    await openGroupCase(toProposeCaseRequest(values));
    const result = useUnderwritingStore.getState().opening;
    if (result.status === 'success' && result.data?.caseId) {
      navigate(`/staff/underwriting/${encodeURIComponent(result.data.caseId)}`);
    }
  }

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Policies', to: '/staff/policies' }]}
        title="Propose a group scheme"
        description="An underwriter decides it, and cover starts when the employer&apos;s first premium clears."
      />

      {!canUnderwrite && (
        <NoAccess what="Setting up a group scheme" who="underwriters" />
      )}

      {canUnderwrite && (
      <form className="max-w-2xl space-y-4 px-6 pb-8" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
        <FormField label="Policyholder (the employer or association)" error={errors.policyholderPartyId?.message}>
          <Controller
            control={control}
            name="policyholderPartyId"
            render={({ field }) => (
              <PartyPicker
                value={field.value || null}
                onChange={(partyId) => field.onChange(partyId ?? '')}
                placeholder="Search for the employer by name"
              />
            )}
          />
          <p className="mt-1 text-xs text-subtle-foreground">
            They own the contract and pay the premium. They are not a life assured — the lives
            are the schedule below.
          </p>
        </FormField>

        <FormField label="Product" error={errors.productId?.message ?? errors.productVersionId?.message}>
          {isInitialLoad(products) ? (
            <p className="text-xs text-muted-foreground">Loading products…</p>
          ) : products.status === 'error' ? (
            <p className="text-xs text-status-danger-fg">Could not load products.</p>
          ) : groupProducts.length === 0 ? (
            // Not an empty dropdown: a control offering nothing looks broken, and
            // the fix is a product-authoring task on another screen.
            <p className="text-xs text-muted-foreground">
              No group-life products exist yet.{' '}
              <Link to="/staff/products/new" className="underline">
                Author one
              </Link>{' '}
              before setting up a scheme.
            </p>
          ) : (
            <Select
              {...register('productId')}
            >
              <option value="">Select a group product</option>
              {groupProducts.map((p) => (
                <option key={p.productId} value={p.productId}>
                  {p.productName} ({p.productCode})
                </option>
              ))}
            </Select>
          )}
        </FormField>

        {/* --- How the scheme values anybody ---------------------------------- */}
        <fieldset className="border-t border-border pt-3">
          <legend className="pr-2 text-xs font-medium tracking-wide text-subtle-foreground uppercase">
            Benefit basis
          </legend>
          <p className="mb-2.5 text-xs text-muted-foreground">
            One basis for the whole scheme. Flat suits SACCO, funeral and credit-linked cover;
            a salary multiple is standard for employer schemes; graded splits by staff category.
          </p>

          <div className="grid gap-3 sm:grid-cols-2">
            <FormField label="Basis">
              <Select
                {...register('benefitBasis')}
              >
                <option value="FLAT">Flat — the same benefit for everyone</option>
                <option value="SALARY_MULTIPLE">Salary multiple</option>
                <option value="GRADED">Graded by staff category</option>
              </Select>
            </FormField>

            <FormField label="Currency" error={errors.currency?.message}>
              <Input
                className="uppercase"
                {...register('currency')}
              />
            </FormField>

            {benefitBasis === 'FLAT' && (
              <FormField label="Benefit per member" error={errors.flatBenefitAmount?.message}>
                <Input
                  inputMode="decimal"
                  
                  placeholder="5000000.00"
                  {...register('flatBenefitAmount')}
                />
              </FormField>
            )}

            {benefitBasis === 'SALARY_MULTIPLE' && (
              <FormField label="Multiple of annual salary" error={errors.salaryMultiple?.message}>
                <Input
                  inputMode="decimal"
                  
                  placeholder="3"
                  {...register('salaryMultiple')}
                />
              </FormField>
            )}

            <FormField label="Free cover limit (optional)" error={errors.fclAmount?.message}>
              <Input
                inputMode="decimal"
                
                placeholder="100000000.00"
                {...register('fclAmount')}
              />
              {/* Blank and zero are opposites here, so the field says which one
                  blank means rather than leaving it to be guessed. */}
              <p className="mt-1 text-xs text-subtle-foreground">
                Leave blank if this scheme has no limit — everyone is then covered in full with
                no medical evidence.
              </p>
            </FormField>
          </div>

          {benefitBasis === 'GRADED' && (
            <div className="mt-3">
              <div className="mb-1.5 flex items-center justify-between">
                <span className="text-xs font-medium text-muted-foreground">Grade table</span>
                <Button type="button" size="sm" variant="ghost" onClick={() => grades.append(blankGradeRow())}>
                  <Plus />
                  Add grade
                </Button>
              </div>
              {typeof errors.grades?.message === 'string' && (
                <p className="mb-1 text-xs text-status-danger-fg">{errors.grades.message}</p>
              )}
              <div className="space-y-2">
                {grades.fields.map((field, index) => (
                  <div key={field.id} className="grid grid-cols-[1fr_1fr_auto] items-start gap-2">
                    <FormField label="Grade" error={errors.grades?.[index]?.gradeCode?.message}>
                      <Input
                        placeholder="MANAGEMENT"
                        {...register(`grades.${index}.gradeCode`)}
                      />
                    </FormField>
                    <FormField label="Benefit" error={errors.grades?.[index]?.benefitAmount?.message}>
                      <Input
                        inputMode="decimal"
                        
                        placeholder="50000000.00"
                        {...register(`grades.${index}.benefitAmount`)}
                      />
                    </FormField>
                    <Button
                      type="button"
                      size="icon"
                      variant="ghost"
                      className="mt-5"
                      aria-label={`Remove grade ${index + 1}`}
                      onClick={() => grades.remove(index)}
                    >
                      <X />
                    </Button>
                  </div>
                ))}
              </div>
            </div>
          )}
        </fieldset>

        {/* --- The opening schedule ------------------------------------------- */}
        <fieldset className="border-t border-border pt-3">
          <legend className="pr-2 text-xs font-medium tracking-wide text-subtle-foreground uppercase">
            Opening schedule
          </legend>
          <p className="mb-2.5 text-xs text-muted-foreground">
            The lives covered from day one. At least one — a scheme's sum assured is the total
            of its members, so it cannot be issued empty. More can be added afterwards.
          </p>

          {typeof errors.openingSchedule?.message === 'string' && (
            <p className="mb-1 text-xs text-status-danger-fg">{errors.openingSchedule.message}</p>
          )}

          <div className="space-y-2">
            {schedule.fields.map((field, index) => (
              <div key={field.id} className="rounded-md border border-border p-2.5">
                <div className="grid grid-cols-[1fr_auto] items-start gap-2">
                  <FormField
                    label={`Member ${index + 1}`}
                    error={errors.openingSchedule?.[index]?.memberPartyId?.message}
                  >
                    <Controller
                      control={control}
                      name={`openingSchedule.${index}.memberPartyId`}
                      render={({ field: memberField }) => (
                        <PartyPicker
                          value={memberField.value || null}
                          onChange={(partyId) => memberField.onChange(partyId ?? '')}
                          placeholder="Search employees by name"
                        />
                      )}
                    />
                  </FormField>
                  <Button
                    type="button"
                    size="icon"
                    variant="ghost"
                    className="mt-5"
                    aria-label={`Remove member ${index + 1}`}
                    disabled={schedule.fields.length === 1}
                    onClick={() => schedule.remove(index)}
                  >
                    <X />
                  </Button>
                </div>

                {benefitBasis === 'SALARY_MULTIPLE' && (
                  <FormField
                    label="Annual salary"
                    error={errors.openingSchedule?.[index]?.salaryAmount?.message}
                  >
                    <Input
                      inputMode="decimal"
                      
                      placeholder="4000000.00"
                      {...register(`openingSchedule.${index}.salaryAmount`)}
                    />
                  </FormField>
                )}

                {benefitBasis === 'GRADED' && (
                  <FormField label="Grade" error={errors.openingSchedule?.[index]?.gradeCode?.message}>
                    <Select
                      {...register(`openingSchedule.${index}.gradeCode`)}
                    >
                      <option value="">Choose a grade…</option>
                      {(gradeRows ?? [])
                        .map((g) => g.gradeCode)
                        .filter(Boolean)
                        .map((code) => (
                          <option key={code} value={code}>
                            {code}
                          </option>
                        ))}
                    </Select>
                  </FormField>
                )}

                {previews[index] && (
                  <p className="mt-1.5 text-xs text-muted-foreground">
                    Covered for{' '}
                    <strong>{formatMoney(previews[index].covered)}</strong>
                    {previews[index].status === 'EVIDENCE_REQUIRED' && (
                      <>
                        {' '}
                        — capped at the free cover limit; the remaining{' '}
                        {formatMoney(previews[index].excess)} needs medical evidence.
                      </>
                    )}
                  </p>
                )}
              </div>
            ))}
          </div>

          <Button
            type="button"
            size="sm"
            variant="ghost"
            className="mt-2 -ml-2"
            onClick={() => schedule.append(blankMemberRow())}
          >
            <Plus />
            Add member
          </Button>

          {/* This platform's own preview of the figure the server will derive.
              Never sent, and labelled so nobody reads it as an input. */}
          <div className="mt-2 rounded-md border border-border bg-hover px-3 py-2 text-xs">
            <div className="flex items-baseline justify-between gap-4">
              <span className="text-muted-foreground">Total sum insured</span>
              <strong>{formatMoney({ amount: runningTotal, currencyCode: currency || 'TZS' })}</strong>
            </div>
            <p className="mt-0.5 text-xs text-subtle-foreground">
              Derived from the schedule — the platform calculates it, nobody types it.
              {unpriced > 0 && ` ${unpriced} row${unpriced === 1 ? '' : 's'} not yet priced.`}
              {overLimit > 0 &&
                ` ${overLimit} member${overLimit === 1 ? '' : 's'} over the free cover limit.`}
            </p>
          </div>
        </fieldset>

        {/* --- Premium and term ----------------------------------------------- */}
        <fieldset className="border-t border-border pt-3">
          <legend className="pr-2 text-xs font-medium tracking-wide text-subtle-foreground uppercase">
            Premium and term
          </legend>

          <div className="grid gap-3 sm:grid-cols-2">
            <FormField label="Premium" error={errors.premiumAmount?.message}>
              <Input
                inputMode="decimal"
                
                placeholder="1200000.00"
                {...register('premiumAmount')}
              />
            </FormField>
            <FormField label="Premium currency" error={errors.premiumCurrency?.message}>
              <Input
                className="uppercase"
                {...register('premiumCurrency')}
              />
            </FormField>
            <FormField label="Frequency">
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
            <FormField label="Risk commences" error={errors.commencementDate?.message}>
              <Controller
                control={control}
                name="commencementDate"
                render={({ field }) => (
                  <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />
                )}
              />
              <p className="mt-1 text-xs text-subtle-foreground">
                Blank means today. Backdating is fine; a future date is not supported yet.
              </p>
            </FormField>
            <FormField label="Term in months (optional)" error={errors.policyTermMonths?.message}>
              <Input
                inputMode="numeric"
                
                placeholder="Leave blank — most schemes renew annually"
                {...register('policyTermMonths')}
              />
            </FormField>
            <FormField label="Note for the record (optional)" error={errors.reasonForManualIssue?.message}>
              <Input
                placeholder="Signed schedule received 1 September"
                {...register('reasonForManualIssue')}
              />
            </FormField>
          </div>
        </fieldset>

        {issuing.status === 'error' && issuing.error && (
          <p role="alert" className="text-xs text-status-danger-fg">
            {issuing.error.detail ?? issuing.error.title}
          </p>
        )}

        <div className="flex items-center gap-2 border-t border-border pt-4">
          <Button type="submit" variant="primary" pending={issuing.status === 'loading'}>
            Propose scheme
          </Button>
          <Button asChild type="button" variant="ghost">
            <Link to="/staff/policies">Cancel</Link>
          </Button>
        </div>
      </form>
      )}
    </>
  );
}

/**
 * Sum a list of 2dp decimal strings exactly.
 *
 * In scaled integers rather than `reduce((a, b) => a + Number(b))`, for the same
 * reason `groupBenefitPreview` multiplies that way: this is a sum insured, and
 * `lib/money.ts` exists because a double silently mangles decimals the backend
 * stores exactly. A 500-life schedule is 500 additions of error.
 */
function sumAmounts(amounts: string[]): string {
  let cents = 0n;
  for (const amount of amounts) {
    const [whole = '0', fraction = ''] = amount.split('.');
    cents += BigInt(whole + fraction.padEnd(2, '0').slice(0, 2));
  }
  const text = cents.toString().padStart(3, '0');
  return `${text.slice(0, -2)}.${text.slice(-2)}`;
}
