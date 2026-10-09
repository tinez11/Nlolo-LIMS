import { zodResolver } from '@hookform/resolvers/zod';
import { Check } from 'lucide-react';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { PRODUCT_CATEGORIES, type ProductCategory } from '@/api/types';
import { canAuthorProducts, readIdentity } from '@/auth/claims';
import { PageHeader } from '@/components/PageHeader';
import { NoAccess } from '@/components/states';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { useProductStore } from '@/store/productStore';
import {
  blankCreateProductForm,
  createProductFormSchema,
  toApiRequest,
  type CreateProductFormValues,
} from './createProductForm';
import { PublishVersionForm } from './PublishVersionForm';
import { Input, Select } from '@/components/ui/input';
import { InlineError } from '@/components/InlineError';
import { PORTFOLIO_CODES, PORTFOLIO_LABEL, portfolioDefaultFor } from '@/lib/ifrs17';

/**
 * A genuinely two-phase flow, not a stylistic choice: `GET /products` only
 * returns ACTIVE products, and a product starts DRAFT -- there is no
 * `GET /products/{id}` either, so a freshly created product is unreadable
 * through ANY endpoint on this platform until a version is published. Phase 2
 * cannot be a separate page navigated to afterward, because there would be
 * nothing there to fetch; it has to run off the phase-1 response directly.
 */
export function CreateProductPage() {
  const navigate = useNavigate();
  const canAuthor = canAuthorProducts(readIdentity(useAuth().user?.access_token));

  const createProduct = useProductStore((s) => s.createProduct);
  const resetCreateProduct = useProductStore((s) => s.resetCreateProduct);
  const creating = useProductStore((s) => s.creating);

  useEffect(() => {
    resetCreateProduct();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const created = creating.status === 'success' ? creating.data : null;

  const {
    register,
    handleSubmit,
    setValue,
    formState: { errors, dirtyFields },
  } = useForm<CreateProductFormValues>({
    resolver: zodResolver(createProductFormSchema),
    defaultValues: blankCreateProductForm(),
  });

  async function onSubmit(values: CreateProductFormValues) {
    await createProduct(toApiRequest(values));
  }

  return (
    <>
      {/* Refused BEFORE the form, not on submit. This page fetches nothing on mount, so
          without this a staff member without ADMIN would fill in a product code, a name, a
          category and a currency, and learn only at the end that they may not do this. */}
      {!canAuthor && (
        <NoAccess what="Authoring a product" who="administrators" />
      )}

      {canAuthor && (<>
      <PageHeader
        breadcrumb={[{ label: 'Products', to: '/staff/products' }]}
        title="New product"
        description="Two steps: define the product, then publish a version -- a product with no version is invisible everywhere else in this console."
      />

      <div className="max-w-xl space-y-5 px-6 pb-8">
        <Step number={1} title="Product definition" done={created !== null}>
          {created === null ? (
            <form className="space-y-4" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
              <FormField label="Product code" error={errors.productCode?.message}>
                <Input
                  className="font-mono"
                  placeholder="NEW-TERM-01"
                  {...register('productCode')}
                />
              </FormField>
              <FormField label="Product name" error={errors.productName?.message}>
                <Input
                  {...register('productName')}
                />
              </FormField>
              <FormField label="Category">
                <Select
                  {...register('category', {
                    // The portfolio follows the category until somebody picks one by hand.
                    onChange: (e) => {
                      if (!dirtyFields.portfolioCode) {
                        setValue('portfolioCode',
                          portfolioDefaultFor(e.target.value as CreateProductFormValues['category']));
                      }
                    },
                  })}
                >
                  {PRODUCT_CATEGORIES.map((c: ProductCategory) => (
                    <option key={c} value={c}>
                      {c.replace(/_/g, ' ')}
                    </option>
                  ))}
                </Select>
              </FormField>
              <FormField
                label="IFRS 17 portfolio"
                hint="Contracts with similar risks, managed together. With the year of issue and expected profitability it decides each policy's group. A product that keeps a savings account must be SAV (savings), DEP (fixed deposit) or PEN (pension) — it is booked as savings, not insurance."
                error={errors.portfolioCode?.message}
              >
                <Select {...register('portfolioCode')}>
                  {PORTFOLIO_CODES.map((p) => (
                    <option key={p} value={p}>
                      {PORTFOLIO_LABEL[p]}
                    </option>
                  ))}
                </Select>
              </FormField>
              <FormField label="Default currency" error={errors.defaultCurrency?.message}>
                <Input
                  className="w-24 uppercase"
                  {...register('defaultCurrency')}
                />
              </FormField>

              {creating.status === 'error' && creating.error && (
                <InlineError error={creating.error}>
                  {/*
                    A duplicate code is very often a code taken by a product nobody can see.
                    `GET /products` returns ACTIVE products only, and a product is ACTIVE only
                    once a version is published -- so abandoning this wizard's second phase
                    leaves a DRAFT that holds the code and appears in no list. Reported from
                    the console as "already exists ... but is not on the list", which is
                    exactly right and was impossible to work out from the message alone.
                  */}
                  {creating.error.kind === 'conflict' && (
                    <p className="mt-1.5">
                      If it is not in the products list, it is an unpublished draft: the list
                      shows only products with a published version. Use a different code, or
                      ask an administrator to publish or remove the draft.
                    </p>
                  )}
                </InlineError>
              )}

              <Button type="submit" variant="primary" pending={creating.status === 'loading'}>
                Create product
              </Button>
            </form>
          ) : (
            <p className="text-xs text-muted-foreground">
              {created.productName} ({created.productCode}) — DRAFT
            </p>
          )}
        </Step>

        <Step number={2} title="Publish a version" done={false} disabled={created === null}>
          {created?.productId && (
            <PublishVersionForm
              productId={created.productId}
              category={created.category ?? 'TERM_LIFE'}
              onPublished={() => navigate(`../${created.productId}`, { relative: 'path' })}
            />
          )}
        </Step>
      </div>
      </>)}
    </>
  );
}

function Step({
  number,
  title,
  done,
  disabled,
  children,
}: {
  number: number;
  title: string;
  done: boolean;
  disabled?: boolean;
  children: React.ReactNode;
}) {
  return (
    <section className={`rounded-lg border border-border bg-surface ${disabled ? 'opacity-50' : ''}`}>
      <div className="flex items-center gap-2 border-b border-border px-4 py-3">
        <span className="grid size-5 place-items-center rounded-full bg-selected text-xs font-semibold">
          {done ? <Check className="size-3" /> : number}
        </span>
        <h2 className="text-sm font-semibold">{title}</h2>
      </div>
      <div className="p-4">{children}</div>
    </section>
  );
}
