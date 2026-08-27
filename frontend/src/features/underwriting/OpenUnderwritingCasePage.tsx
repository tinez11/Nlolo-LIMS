import { ArrowLeft } from 'lucide-react';
import { useEffect } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate } from 'react-router-dom';
import { PageHeader } from '@/components/PageHeader';
import { PartyPicker } from '@/components/PartyPicker';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectProductSnapshot, useProductStore } from '@/store/productStore';
import { useUnderwritingStore } from '@/store/underwritingStore';
import { blankOpenCaseForm, openCaseFormSchema, toApiRequest, type OpenCaseFormValues } from './openCaseForm';

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
  } = useForm<OpenCaseFormValues>({
    resolver: zodResolver(openCaseFormSchema),
    defaultValues: blankOpenCaseForm(),
  });

  // eslint-disable-next-line react-hooks/incompatible-library -- see IssuePolicyPage
  const productId = watch('productId');
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
        <FormField label="Applicant party id" error={errors.applicantPartyId?.message}>
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
              placeholder="1500000.00"
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
