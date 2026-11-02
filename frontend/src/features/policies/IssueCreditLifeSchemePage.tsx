import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo, useState } from 'react';
import { listAgents } from '@/api/distribution';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { canUnderwriteGroupSchemes, readIdentity } from '@/auth/claims';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { FormLayout } from '@/components/FormLayout';
import { PageHeader } from '@/components/PageHeader';
import { PartyPicker } from '@/components/PartyPicker';
import { ProductChooser } from '@/components/ProductChooser';
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
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { UnsavedGuard } from '@/components/UnsavedGuard';

/**
 * Setting up a lender's credit-life scheme.
 *
 * <p>Until this page existed there was no way to create one from the console at all: the basis
 * picker on the group-scheme form offers three values and none of them is AMORTISING_LOAN, so
 * every credit-life scheme on this platform was made with curl. See
 * `creditLifeSchemeIssueForm` for why this is a separate page on a separate endpoint rather than
 * a fourth option on that form.
 *
 * <p><b>The form is short on purpose, and it collects no borrowers and no price.</b> A credit-life
 * scheme is an agreement with a lender -- its free cover limit and when it started -- and it exists
 * before any borrower does; the price is the lender's yearly agreement, added on the scheme page. The book arrives monthly by file and never stops
 * arriving, which is why this page's last act is to hand over to the page that reads them.
 *
 * <p>It used to demand one opening borrower, because the service demanded a non-empty schedule —
 * a rule written for employer schemes, where the schedule IS the contract. Here it made somebody
 * type a life they then met again on the member roll without recognising them.
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
    formState: { errors, isDirty, isSubmitting },
  } = useForm<CreditLifeSchemeIssueFormValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(creditLifeSchemeIssueFormSchema()),
    defaultValues: blankCreditLifeSchemeIssueForm(),
  });

  const productId = useWatch({ control, name: 'productId' });
  const issuanceBasis = useWatch({ control, name: 'issuanceBasis' });
  const lenderPartyId = useWatch({ control, name: 'policyholderPartyId' });

  /*
    Is the lender a registered agent? If so the scheme is issued with the lender earning its
    commission (spec 2.8). Stored WITH the party it answers for, and read back only while that is
    still the lender chosen -- so picking another lender never shows the last one's answer, and
    nothing is reset synchronously inside an effect.
  */
  const [lenderAgentLookup, setLenderAgentLookup] = useState<{ partyId: string; agentId: string | null } | null>(null);
  useEffect(() => {
    if (!lenderPartyId) return;
    let cancelled = false;
    void listAgents({ partyId: lenderPartyId })
      .then((page) => page.items[0]?.agentId ?? null)
      .catch(() => null)
      .then((agentId) => {
        if (!cancelled) setLenderAgentLookup({ partyId: lenderPartyId, agentId });
      });
    return () => {
      cancelled = true;
    };
  }, [lenderPartyId]);
  const lenderAgentId =
    lenderAgentLookup && lenderAgentLookup.partyId === lenderPartyId ? lenderAgentLookup.agentId : null;
  const lenderAgentKnown = !!lenderAgentLookup && lenderAgentLookup.partyId === lenderPartyId;

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
    await issueScheme(toIssueRequest(values, lenderAgentId));
    const result = usePolicyStore.getState().issuingScheme;
    if (result.status === 'success' && result.data?.policyNumber) {
      navigate(`/staff/credit-life-schemes/${encodeURIComponent(result.data.policyNumber)}`);
    }
  }

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Policies', to: '/staff/policies' }]}
        title="Set up a credit-life scheme"
        description="A lender's book. Set up once here, priced by the lender's yearly agreement; the borrowers arrive every month on a file."
      />

      {!canUnderwrite && <NoAccess what="Setting up a credit-life scheme" who="underwriters" />}

      {canUnderwrite && (
        <FormLayout formId="credit-life-scheme-form" outlineLabel="Credit-life scheme sections">
        <form className="space-y-4" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
          <UnsavedGuard when={isDirty && !isSubmitting} what="This credit-life scheme" />
          <div data-outline="Lender and product" className="space-y-4">
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
            <p className="mt-1 text-xs text-subtle-foreground">
              They hold the contract and pay the premium, and they are the beneficiary of every
              claim on it — a credit-life payout settles the borrower&rsquo;s debt, so it goes to
              the lender rather than to a family.
            </p>
            {lenderPartyId && lenderAgentKnown && (
              <p className="mt-1 text-xs text-muted-foreground">
                {lenderAgentId
                  ? 'Commission: paid to the lender, a registered agent, at the rate its yearly agreement states.'
                  : 'Commission: the lender is not a registered agent yet, so the scheme starts direct. Register it on the scheme page.'}
              </p>
            )}
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
              <Controller
                control={control}
                name="productId"
                render={({ field }) => (
                  <ProductChooser products={creditLifeProducts} value={field.value} onChange={(id) => field.onChange(id)} />
                )}
              />
            )}
          </FormField>
          </div>

          {/* --- What the insurer agreed with the lender ----------------------- */}
          <fieldset data-outline="Scheme terms" className="border-t border-border pt-3">
            <legend className="pr-2 text-eyebrow text-subtle-foreground uppercase">
              Scheme terms
            </legend>
            <p className="mb-2.5 text-xs text-muted-foreground">
              Agreed once, per lender. The price &mdash; the rate, the borrowers&rsquo; ages, the loan lengths and the
              lender&rsquo;s commission &mdash; is the yearly agreement, added on the scheme page next. A borrower is
              covered for what they still owe.
            </p>

            <div className="grid gap-3 sm:grid-cols-2">
              <FormField label="Currency" error={errors.currency?.message}>
                <Input className="uppercase" {...register('currency')} />
              </FormField>

              <FormField label="Free cover limit (optional)" error={errors.fclAmount?.message}>
                <Input placeholder="600000000.00" {...register('fclAmount')} />
                <p className="mt-1 text-xs text-subtle-foreground">
                  Leave blank for a scheme with no limit. Blank is not zero — a limit of zero
                  would send every borrower to underwriting.
                </p>
              </FormField>

              <FormField label="Risk commences" error={errors.commencementDate?.message}>
                <Controller
                  control={control}
                  name="commencementDate"
                  render={({ field }) => (
                    <DatePicker value={field.value} onChange={(iso) => field.onChange(iso ?? '')} />
                  )}
                />
                <p className="mt-1 text-xs text-subtle-foreground">
                  On or before the oldest loan on the book. A borrower joins on the day they were
                  lent to, and nobody can join a scheme that did not exist yet.
                </p>
              </FormField>

              <FormField label="Why this scheme is in force" error={errors.issuanceBasis?.message}>
                <Select {...register('issuanceBasis')}>
                  <option value="">Choose…</option>
                  {IN_FORCE_ISSUANCE_BASES.map((value) => (
                    <option key={value} value={value}>
                      {value === 'MIGRATION'
                        ? 'Migration — the lender already runs this book'
                        : value === 'CONVERSION'
                          ? 'Conversion — continuous cover from another contract'
                          : 'Reinstatement — arrears have been settled'}
                    </option>
                  ))}
                  <option value="OFFER">None — issue it as an offer</option>
                </Select>
                <p className="mt-1 text-xs text-muted-foreground">
                  Recorded with your name as the underwriter who set this scheme up, for compliance.
                </p>
                {/* The wall, said before somebody hits it rather than after. An offer sits
                    PROPOSED until the first premium clears, enrolment requires the scheme IN
                    FORCE, and this console has no action anywhere that accepts an offer. */}
                {issuanceBasis === 'OFFER' && (
                  <p className="mt-1 text-xs text-status-warning-fg">
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

          {issuing.status === 'error' && issuing.error && <ErrorPanel error={issuing.error} />}

          <div className="flex items-center gap-2 border-t border-border pt-3">
            <Button type="submit" variant="primary" pending={issuing.status === 'loading'}>
              Set up the scheme
            </Button>
            <p className="text-xs text-muted-foreground">
              Lands on the scheme&rsquo;s page, where the lender&rsquo;s yearly agreement is added before the first
              file.
            </p>
          </div>
        </form>
        </FormLayout>
      )}
    </>
  );
}
