import { useId } from 'react';
import { PRODUCT_CATEGORIES, type ProductCategory, type ProductSummary } from '@/api/types';
import { Select } from '@/components/ui/input';
import { humanizeStatus } from '@/lib/status';

/**
 * A list's product filter in two steps, category then product (2026-10-26): the compact cousin of `ProductChooser`
 * for filter rows, where a card list would push the results off the screen.
 *
 * Choosing a category narrows the product select to that category's products and drops a chosen product that is not
 * in it. Only categories that have a product are offered. The labels sit beside their selects rather than wrapping
 * them, for the reason `ReportFilterBar` records: a wrapping label takes its name from every option inside it.
 */
export function ProductFilter({
  products,
  category,
  productId,
  onChange,
  compact = false,
}: {
  products: readonly ProductSummary[];
  category: string;
  productId: string;
  onChange: (next: { category: string; productId: string }) => void;
  /** In a list's filter row: the labels are read, not shown, and each select's first option says what it filters. */
  compact?: boolean;
}) {
  const id = useId();
  const present = new Set(products.map((p) => p.category));
  const categories = PRODUCT_CATEGORIES.filter((c) => present.has(c));
  // A chosen product implies its category, so a link carrying only ?productId= still shows the pair consistently.
  const effectiveCategory = category || (products.find((p) => p.productId === productId)?.category ?? '');
  const shown = [...products]
    .filter((p) => !effectiveCategory || p.category === effectiveCategory)
    .sort((a, b) => (a.productName ?? '').localeCompare(b.productName ?? ''));

  return (
    <>
      <div className={compact ? 'contents' : 'flex flex-col gap-1 text-xs'}>
        <label htmlFor={`${id}-category`} className={compact ? 'sr-only' : undefined}>Product category</label>
        <Select
          id={`${id}-category`}
          inputSize="sm"
          className="w-auto"
          value={effectiveCategory}
          onChange={(e) => {
            const next = e.target.value as ProductCategory | '';
            const keep = products.find((p) => p.productId === productId)?.category === next;
            onChange({ category: next, productId: next && keep ? productId : '' });
          }}
        >
          <option value="">All categories</option>
          {categories.map((c) => (
            <option key={c} value={c}>
              {humanizeStatus(c)}
            </option>
          ))}
        </Select>
      </div>
      <div className={compact ? 'contents' : 'flex flex-col gap-1 text-xs'}>
        <label htmlFor={`${id}-product`} className={compact ? 'sr-only' : undefined}>Product</label>
        <Select
          id={`${id}-product`}
          inputSize="sm"
          className="w-auto max-w-56"
          value={productId}
          onChange={(e) => onChange({ category: effectiveCategory, productId: e.target.value })}
        >
          <option value="">{effectiveCategory ? `All ${humanizeStatus(effectiveCategory).toLowerCase()}` : 'All products'}</option>
          {shown.map((p) => (
            <option key={p.productId} value={p.productId}>
              {p.productName}
            </option>
          ))}
        </Select>
      </div>
    </>
  );
}
