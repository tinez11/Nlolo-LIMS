import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft, Check } from 'lucide-react';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { PRODUCT_CATEGORIES, type ProductCategory } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
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
    formState: { errors },
  } = useForm<CreateProductFormValues>({
    resolver: zodResolver(createProductFormSchema),
    defaultValues: blankCreateProductForm(),
  });

  async function onSubmit(values: CreateProductFormValues) {
    await createProduct(toApiRequest(values));
  }

  return (
    <>
      <div className="px-6 pt-6">
        <Button asChild variant="ghost" size="sm" className="-ml-2">
          <Link to=".." relative="path">
            <ArrowLeft />
            All products
          </Link>
        </Button>
      </div>

      <PageHeader
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
                  {...register('category')}
                >
                  {PRODUCT_CATEGORIES.map((c: ProductCategory) => (
                    <option key={c} value={c}>
                      {c.replace(/_/g, ' ')}
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
                <div
                  role="alert"
                  className="rounded-md bg-status-danger-bg px-3 py-2 text-xs text-status-danger-fg"
                >
                  {creating.error.detail ?? creating.error.title}
                  {creating.error.traceId && (
                    <span className="ml-2 font-mono text-[10px] opacity-80">
                      ({creating.error.traceId})
                    </span>
                  )}
                </div>
              )}

              <Button type="submit" variant="primary" disabled={creating.status === 'loading'}>
                {creating.status === 'loading' ? 'Creating…' : 'Create product'}
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
        <span className="grid size-5 place-items-center rounded-full bg-selected text-[11px] font-semibold">
          {done ? <Check className="size-3" /> : number}
        </span>
        <h2 className="text-sm font-semibold">{title}</h2>
      </div>
      <div className="p-4">{children}</div>
    </section>
  );
}
