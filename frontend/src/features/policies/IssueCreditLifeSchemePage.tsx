import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo, useState } from 'react';
import { listAgents } from '@/api/distribution';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { canUnderwriteGroupSchemes, readIdentity } from '@/auth/claims';
import { DatePicker } from '@/components/DatePicker';
import { FormField, RequiredNote } from '@/components/FormField';
import { FormLayout } from '@/components/FormLayout';
import { PageHeader } from '@/components/PageHeader';
import { PartyPicker } from '@/components/PartyPicker';
import { ProductChooser } from '@/components/ProductChooser';
import { ErrorPanel, NoAccess } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectIssuingScheme, usePolicyStore } from '@/store/policyStore';
import { selectProductSnapshot, useProductStore } from '@/store/productStore';
import {
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
        description="Once per lender. Borrowers then arrive on monthly files."
      />

      {!canUnderwrite && <NoAccess what="Setting up a credit-life scheme" who="underwriters" />}

      {canUnderwrite && (
        <FormLayout formId="credit-life-scheme-form" outlineLabel="Credit-life scheme sections">
        <form className="space-y-4" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
          <RequiredNote />
          <UnsavedGuard when={isDirty && !isSubmitting} what="This credit-life scheme" />
          <div data-outline="Lender and product" className="space-y-4">
          <FormField required label="The lender" error={errors.policyholderPartyId?.message}>
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
              Holds the contract, pays the premium and receives the claims.
            </p>
            {lenderPartyId && lenderAgentKnown && (
              <p className="mt-1 text-xs text-muted-foreground">
                {lenderAgentId
                  ? 'Commission: paid to the lender at its agreement’s rate.'
                  : 'Commission: none until the lender is registered as an agent on the scheme page.'}
              </p>
            )}
          </FormField>

          <FormField required
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
              The rate, ages, loan lengths and commission are set in the yearly agreement on the next page.
            </p>

            <div className="grid gap-3 sm:grid-cols-2">
              <FormField required label="Currency" error={errors.currency?.message}>
                <Input className="uppercase" {...register('currency')} />
              </FormField>

              <FormField label="Free cover limit" error={errors.fclAmount?.message}>
                <Input placeholder="600000000.00" {...register('fclAmount')} />
                <p className="mt-1 text-xs text-subtle-foreground">Leave blank for no limit.</p>
              </FormField>

              <FormField required label="Risk commences" error={errors.commencementDate?.message}>
                <Controller
                  control={control}
                  name="commencementDate"
                  render={({ field }) => (
                    <DatePicker value={field.value} onChange={(iso) => field.onChange(iso ?? '')} />
                  )}
                />
                <p className="mt-1 text-xs text-subtle-foreground">On or before the oldest loan&rsquo;s start date.</p>
              </FormField>

              {/* No "why this scheme is in force" (2026-11-02): a credit-life scheme is always in force on set-up. */}
              <FormField label="Note for the record" error={errors.reasonForManualIssue?.message}>
                <Input placeholder="Onboarding BUMACO's existing book" {...register('reasonForManualIssue')} />
              </FormField>
            </div>
          </fieldset>

          {issuing.status === 'error' && issuing.error && <ErrorPanel error={issuing.error} />}

          <div className="flex items-center gap-2 border-t border-border pt-3">
            <Button type="submit" variant="primary" pending={issuing.status === 'loading'}>
              Set up the scheme
            </Button>
            <p className="text-xs text-muted-foreground">Next: add the lender&rsquo;s yearly agreement.</p>
          </div>
        </form>
        </FormLayout>
      )}
    </>
  );
}
