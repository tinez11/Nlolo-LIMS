import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft } from 'lucide-react';
import { useEffect, useMemo } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { canUnderwriteGroupSchemes, readIdentity } from '@/auth/claims';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { PageHeader } from '@/components/PageHeader';
import { PartyPicker } from '@/components/PartyPicker';
import { ErrorPanel, NoAccess } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectIssuingScheme, usePolicyStore } from '@/store/policyStore';
import { selectProductSnapshot, useProductStore } from '@/store/productStore';
import {
  IN_FORCE_ISSUANCE_BASES,
  blankCreditLifeSchemeIssueForm,
  creditLifeSchemeIssueFormSchema,
  toIssueRequest,
  type CreditLifeSchemeIssueFormValues,
} from './creditLifeSchemeIssueForm';

/**
 * Setting up a lender's credit-life scheme.
 *
 * <p>Until this page existed there was no way to create one from the console at all: the basis
 * picker on the group-scheme form offers three values and none of them is AMORTISING_LOAN, so
 * every credit-life scheme on this platform was made with curl. See
 * `creditLifeSchemeIssueForm` for why this is a separate page on a separate endpoint rather than
 * a fourth option on that form.
 *
 * <p><b>The form is short on purpose.</b> A credit-life scheme is an agreement with a lender —
 * the rate, the free cover limit, the interest method — plus the one borrower the platform
 * requires to open it. Everybody else arrives by monthly file, which is why this page's last act
 * is to hand over to the one that reads them.
 *
 * <p>Like manual issue, this endpoint declares no `Idempotency-Key`: a second identical
 * submission genuinely creates a second scheme, so disabling the button while in flight is the
 * only guard there is.
 */
export function IssueCreditLifeSchemePage() {
  // Same gate as the group-scheme form and the same reason: POST /group-schemes is
  // hasRole('UNDERWRITER'), and offering a form that can only end in a 403 is worse than saying
  // so.
  const canUnderwrite = canUnderwriteGroupSchemes(readIdentity(useAuth().user?.access_token));
  const navigate = useNavigate();

  const issueScheme = usePolicyStore((s) => s.issueGroupScheme);
  const resetIssue = usePolicyStore((s) => s.resetIssueGroupScheme);
  const issuing = usePolicyStore(selectIssuingScheme);

  const products = useProductStore((s) => s.list);
  const loadProducts = useProductStore((s) => s.loadList);
  const loadSnapshot = useProductStore((s) => s.loadSnapshot);

  // The slot outlives this page's mount, so a previous visit's rejection would otherwise greet
  // the next one.
  useEffect(() => {
    resetIssue();
    void loadProducts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const {
    register,
    handleSubmit,
    control,
    setValue,
    formState: { errors },
  } = useForm<CreditLifeSchemeIssueFormValues>({
    resolver: zodResolver(creditLifeSchemeIssueFormSchema()),
    defaultValues: blankCreditLifeSchemeIssueForm(),
  });

  const productId = useWatch({ control, name: 'productId' });
  const issuanceBasis = useWatch({ control, name: 'issuanceBasis' });

  // CREDIT_LIFE only. The service refuses any other category with a 409, and letting somebody
  // pick a term-life product and be told afterwards is the version of this that wastes a form.
  const creditLifeProducts = useMemo(
    () => (products.data ?? []).filter((p) => p.category === 'CREDIT_LIFE'),
    [products.data],
  );

  const snapshot = useProductStore(selectProductSnapshot(productId));
  useEffect(() => {
    if (productId) void loadSnapshot(productId);
  }, [productId, loadSnapshot]);

  // The request carries the VERSION, not the product: a scheme is issued against the terms in
  // force, and resolving that here keeps it out of anybody's hands.
  const resolvedVersionId = snapshot.data?.productVersionId ?? '';
  useEffect(() => {
    setValue('productVersionId', resolvedVersionId);
  }, [resolvedVersionId, setValue]);

  /**
   * Issues the scheme and goes straight to its monthly files.
   *
   * <p>Not to the policy record, which answers "one contract, N lives, total X" — true, and not
   * what somebody who has just onboarded a lender is about to do. The next act is always the
   * first enrolment file.
   */
  async function onSubmit(values: CreditLifeSchemeIssueFormValues) {
    await issueScheme(toIssueRequest(values));
    const result = usePolicyStore.getState().issuingScheme;
    if (result.status === 'success' && result.data?.policyNumber) {
      navigate(`/staff/credit-life-schemes/${encodeURIComponent(result.data.policyNumber)}`);
    }
  }

  return (
    <>
      <div className="px-6 pt-6">
        <Button asChild variant="ghost" size="sm" className="-ml-2">
          <Link to="/staff/policies">
            <ArrowLeft />
            All policies
          </Link>
        </Button>
      </div>

      <PageHeader
        title="Set up a credit-life scheme"
        description="A lender's book. The terms are agreed once here; the borrowers arrive every month on a file."
      />

      {!canUnderwrite && <NoAccess what="Setting up a credit-life scheme" who="underwriters" />}

      {canUnderwrite && (
        <form className="max-w-2xl space-y-4 px-6 pb-8" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
          <FormField label="The lender" error={errors.policyholderPartyId?.message}>
            <Controller
              control={control}
              name="policyholderPartyId"
              render={({ field }) => (
                <PartyPicker
                  value={field.value || null}
                  onChange={(partyId) => field.onChange(partyId ?? '')}
                  placeholder="Search for the lender by name"
                />
              )}
            />
            <p className="mt-1 text-[11px] text-subtle-foreground">
              They hold the contract and pay the premium, and they are the beneficiary of every
              claim on it — a credit-life payout settles the borrower&rsquo;s debt, so it goes to
              the lender rather than to a family.
            </p>
          </FormField>

          <FormField
            label="Product"
            error={errors.productId?.message ?? errors.productVersionId?.message}
          >
            {isInitialLoad(products) ? (
              <p className="text-xs text-muted-foreground">Loading products…</p>
            ) : products.status === 'error' ? (
              <p className="text-xs text-status-danger-fg">Could not load products.</p>
            ) : creditLifeProducts.length === 0 ? (
              // Not an empty dropdown: a control offering nothing looks broken, and the fix is a
              // product-authoring task on another screen.
              <p className="text-xs text-muted-foreground">
                No credit-life products exist yet.{' '}
                <Link to="/staff/products/new" className="underline">
                  Author one
                </Link>{' '}
                before setting up a scheme.
              </p>
            ) : (
              <Select {...register('productId')}>
                <option value="">Select a credit-life product</option>
                {creditLifeProducts.map((p) => (
                  <option key={p.productId} value={p.productId}>
                    {p.productName} ({p.productCode})
                  </option>
                ))}
              </Select>
            )}
          </FormField>

          {/* --- What the insurer agreed with the lender ----------------------- */}
          <fieldset className="border-t border-border pt-3">
            <legend className="pr-2 text-[11px] font-medium tracking-wide text-subtle-foreground uppercase">
              Scheme terms
            </legend>
            <p className="mb-2.5 text-[11px] text-muted-foreground">
              Agreed once, per lender. There is no benefit basis to choose: a borrower is covered
              for what they still owe, which falls as they repay.
            </p>

            <div className="grid gap-3 sm:grid-cols-2">
              <FormField label="Premium rate (% of each loan)" error={errors.premiumRatePercent?.message}>
                <Input placeholder="0.5" {...register('premiumRatePercent')} />
                <p className="mt-1 text-[11px] text-subtle-foreground">
                  Charged once per borrower on what they borrowed, not on the declining balance.
                </p>
              </FormField>

              <FormField label="Currency" error={errors.currency?.message}>
                <Input className="uppercase" {...register('currency')} />
              </FormField>

              <FormField label="Interest method" error={errors.interestMethod?.message}>
                <Select {...register('interestMethod')}>
                  <option value="FLAT_RATE">Flat rate — interest on the original principal</option>
                  <option value="REDUCING_BALANCE">Reducing balance</option>
                </Select>
              </FormField>

              <FormField label="Repayment frequency" error={errors.repaymentFrequency?.message}>
                <Select {...register('repaymentFrequency')}>
                  <option value="MONTHLY">Monthly</option>
                  <option value="QUARTERLY">Quarterly</option>
                </Select>
              </FormField>

              <FormField label="Free cover limit (optional)" error={errors.fclAmount?.message}>
                <Input placeholder="600000000.00" {...register('fclAmount')} />
                <p className="mt-1 text-[11px] text-subtle-foreground">
                  Leave blank for a scheme with no limit. Blank is not zero — a limit of zero
                  would send every borrower to underwriting.
                </p>
              </FormField>

              <FormField label="Premium on the opening loan" error={errors.premiumAmount?.message}>
                <Input placeholder="52000.00" {...register('premiumAmount')} />
              </FormField>

              <FormField label="Risk commences" error={errors.commencementDate?.message}>
                <Controller
                  control={control}
                  name="commencementDate"
                  render={({ field }) => (
                    <DatePicker value={field.value} onChange={(iso) => field.onChange(iso ?? '')} />
                  )}
                />
                <p className="mt-1 text-[11px] text-subtle-foreground">
                  On or before the oldest loan on the book. A borrower joins on the day they were
                  lent to, and nobody can join a scheme that did not exist yet.
                </p>
              </FormField>

              <FormField label="Why this scheme is in force" error={errors.issuanceBasis?.message}>
                <Select {...register('issuanceBasis')}>
                  {IN_FORCE_ISSUANCE_BASES.map((value) => (
                    <option key={value} value={value}>
                      {value === 'MIGRATION'
                        ? 'Migration — the lender already runs this book'
                        : value === 'CONVERSION'
                          ? 'Conversion — continuous cover from another contract'
                          : 'Reinstatement — arrears have been settled'}
                    </option>
                  ))}
                  <option value="">None — issue it as an offer</option>
                </Select>
                {/* The wall, said before somebody hits it rather than after. An offer sits
                    PROPOSED until the first premium clears, enrolment requires the scheme IN
                    FORCE, and this console has no action anywhere that accepts an offer. */}
                {issuanceBasis === '' && (
                  <p className="mt-1 text-[11px] text-status-warning-fg">
                    An offer cannot receive an enrolment file until its first premium clears, and
                    nothing in this console can accept one. Pick a basis unless you are recording
                    a scheme that genuinely is not on risk yet.
                  </p>
                )}
              </FormField>
            </div>

            <FormField label="Note for the record (optional)" error={errors.reasonForManualIssue?.message}>
              <Input
                placeholder="Onboarding BUMACO's existing book"
                {...register('reasonForManualIssue')}
              />
            </FormField>
          </fieldset>

          {/* --- The one borrower the platform needs to open the scheme -------- */}
          <fieldset className="border-t border-border pt-3">
            <legend className="pr-2 text-[11px] font-medium tracking-wide text-subtle-foreground uppercase">
              Opening borrower
            </legend>
            <p className="mb-2.5 text-[11px] text-muted-foreground">
              A scheme cannot be created insuring nobody, so it opens with one borrower. Everyone
              else arrives on the monthly file — take the first row of the lender&rsquo;s own
              spreadsheet.
            </p>

            <div className="grid gap-3 sm:grid-cols-2">
              <FormField label="Borrower's full name" error={errors.borrowerName?.message}>
                <Input {...register('borrowerName')} />
                <p className="mt-1 text-[11px] text-subtle-foreground">
                  A name, not a client record. The insurer holds no party for a borrower — their
                  cover belongs to the lender.
                </p>
              </FormField>

              <FormField label="Date of birth" error={errors.borrowerDateOfBirth?.message}>
                <Controller
                  control={control}
                  name="borrowerDateOfBirth"
                  render={({ field }) => (
                    <DatePicker value={field.value} onChange={(iso) => field.onChange(iso ?? '')} />
                  )}
                />
              </FormField>

              <FormField label="Loan account number (optional)" error={errors.loanAccountNumber?.message}>
                <Input {...register('loanAccountNumber')} />
                <p className="mt-1 text-[11px] text-subtle-foreground">
                  Neither real lender file carries one, which is why the insurer mints its own
                  member reference instead.
                </p>
              </FormField>

              <FormField label="Amount borrowed" error={errors.principalAmount?.message}>
                <Input placeholder="2400000.00" {...register('principalAmount')} />
              </FormField>

              <FormField label="Term (months)" error={errors.termMonths?.message}>
                <Input placeholder="18" {...register('termMonths')} />
              </FormField>

              <FormField
                label="Annual interest rate (%)"
                error={errors.annualInterestRatePercent?.message}
              >
                <Input placeholder="0" {...register('annualInterestRatePercent')} />
                <p className="mt-1 text-[11px] text-subtle-foreground">
                  Zero is a real answer on a flat-rate loan: cover declines in a straight line, so
                  no rate is read to value a claim.
                </p>
              </FormField>

              <FormField label="Disbursed on" error={errors.disbursementDate?.message}>
                <Controller
                  control={control}
                  name="disbursementDate"
                  render={({ field }) => (
                    <DatePicker value={field.value} onChange={(iso) => field.onChange(iso ?? '')} />
                  )}
                />
              </FormField>

              <FormField label="First repayment due" error={errors.firstRepaymentDate?.message}>
                <Controller
                  control={control}
                  name="firstRepaymentDate"
                  render={({ field }) => (
                    <DatePicker value={field.value} onChange={(iso) => field.onChange(iso ?? '')} />
                  )}
                />
              </FormField>
            </div>
          </fieldset>

          {issuing.status === 'error' && issuing.error && <ErrorPanel error={issuing.error} />}

          <div className="flex items-center gap-2 border-t border-border pt-3">
            <Button type="submit" disabled={issuing.status === 'loading'}>
              {issuing.status === 'loading' ? 'Setting up…' : 'Set up the scheme'}
            </Button>
            <p className="text-[11px] text-muted-foreground">
              Lands on the scheme&rsquo;s monthly files, ready for the first enrolment.
            </p>
          </div>
        </form>
      )}
    </>
  );
}
