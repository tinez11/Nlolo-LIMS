import { Search } from 'lucide-react';
import { useMemo, useRef, useState } from 'react';
import { PRODUCT_CATEGORIES, type ProductCategory, type ProductSummary } from '@/api/types';
import { FilterChip } from '@/components/FilterChip';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/cn';
import { humanizeStatus } from '@/lib/status';
import { useFieldControl } from './fieldControl';

/**
 * Chooses a product in two steps: its category, then the product itself, each product shown with the
 * description its author wrote (2026-10-26).
 *
 * <p>What it replaces: one long select of "Name (CODE)" across every category, where a staff member
 * told two education plans apart by their codes. The category comes first because that is the
 * question the customer has already answered ("a funeral cover", "a savings plan"); the description
 * is what tells the remaining few apart.
 *
 * <p>The search box looks across EVERY category, not just the chosen one: someone who knows the
 * product's name should not have to know where it is filed. A category with a single product is
 * still a step -- skipping it would make the list jump -- but a screen that only offers one
 * category (group schemes, credit life) shows that category's products straight away.
 *
 * <p>Once a product is chosen it collapses to a card with its description and a Change button,
 * so the form below reads against what was picked. Changing keeps the current choice until a new
 * one is made.
 */
export interface ProductChooserProps {
  products: readonly ProductSummary[];
  /** The selected productId, or '' / null for none. */
  value: string | null | undefined;
  onChange: (productId: string, product: ProductSummary) => void;
  /** Which products this screen may offer -- single-life only on issue, GROUP_LIFE on schemes. */
  allow?: ((p: ProductSummary) => boolean) | undefined;
  disabled?: boolean | undefined;
}

export function ProductChooser({ products, value, onChange, allow, disabled = false }: ProductChooserProps) {
  const offered = useMemo(() => products.filter((p) => !allow || allow(p)), [products, allow]);
  const selected = value ? offered.find((p) => p.productId === value) ?? products.find((p) => p.productId === value) : undefined;

  const [browsing, setBrowsing] = useState(false);
  const [category, setCategory] = useState<ProductCategory | null>(null);
  const [query, setQuery] = useState('');
  const { id: fieldId } = useFieldControl();
  const root = useRef<HTMLDivElement>(null);

  const counts = useMemo(() => {
    const byCategory = new Map<ProductCategory, number>();
    for (const p of offered) if (p.category) byCategory.set(p.category, (byCategory.get(p.category) ?? 0) + 1);
    return byCategory;
  }, [offered]);
  const categories = PRODUCT_CATEGORIES.filter((c) => counts.has(c));
  // One category on offer is no choice at all; the chosen product's own category opens a Change.
  const activeCategory = categories.length === 1 ? categories[0]! : (category ?? selected?.category ?? null);

  const needle = query.trim().toLowerCase();
  const shown = needle
    ? offered.filter((p) => `${p.productName ?? ''} ${p.productCode ?? ''}`.toLowerCase().includes(needle))
    : activeCategory
      ? offered.filter((p) => p.category === activeCategory)
      : [];

  function choose(p: ProductSummary) {
    if (!p.productId) return;
    onChange(p.productId, p);
    setBrowsing(false);
    setQuery('');
    setCategory(null);
    // The list collapses to one card, which can leave the page scrolled past it: bring the card back into view.
    requestAnimationFrame(() => root.current?.scrollIntoView({ block: 'nearest' }));
  }

  if (selected && !browsing) {
    return (
      <div ref={root} className="scroll-mt-24 rounded-md border border-border bg-surface px-3 py-2.5">
        <div className="flex items-start gap-3">
          <div className="min-w-0 flex-1">
            <p className="text-sm font-medium">{selected.productName}</p>
            <p className="text-xs text-muted-foreground">
              <span className="font-mono">{selected.productCode}</span>
              {selected.category && <> · {humanizeStatus(selected.category)}</>}
            </p>
          </div>
          {!disabled && (
            <button
              type="button"
              {...(fieldId ? { id: fieldId } : {})}
              aria-label={`Change product, ${selected.productName ?? ''} chosen`}
              onClick={() => setBrowsing(true)}
              className="shrink-0 rounded-md px-2 py-1 text-xs font-medium text-accent hover:bg-hover"
            >
              Change
            </button>
          )}
        </div>
        <Description text={selected.description} className="mt-1.5" />
      </div>
    );
  }

  if (offered.length === 0) {
    return <p className="text-xs text-muted-foreground">No products on offer here yet.</p>;
  }

  return (
    <div ref={root} className="scroll-mt-24 space-y-2.5">
      <div className="relative">
        <Search aria-hidden className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
        <Input
          {...(fieldId ? { id: fieldId } : {})}
          type="search"
          aria-label="Find a product"
          placeholder="Search every product by name or code"
          value={query}
          disabled={disabled}
          onChange={(e) => setQuery(e.target.value)}
          className="pl-8"
        />
      </div>

      {!needle && categories.length > 1 && (
        <div role="group" aria-label="Product category" className="flex flex-wrap gap-1.5">
          {categories.map((c) => (
            <FilterChip
              key={c}
              active={activeCategory === c}
              onClick={() => setCategory(c)}
              label={
                <>
                  {humanizeStatus(c)}
                  <span className="ml-1.5 tabular-nums opacity-70">{counts.get(c)}</span>
                </>
              }
            />
          ))}
        </div>
      )}

      {!needle && !activeCategory ? (
        <p className="text-xs text-muted-foreground">Choose a category to see its products.</p>
      ) : shown.length === 0 ? (
        <p className="text-xs text-muted-foreground">No product matches “{query.trim()}”.</p>
      ) : (
        <ul aria-label={needle ? 'Matching products' : `${humanizeStatus(activeCategory ?? '')} products`} className="max-h-96 space-y-1.5 overflow-y-auto overscroll-contain">
          {shown.map((p) => {
            const current = p.productId === value;
            return (
              <li key={p.productId}>
                <button
                  type="button"
                  disabled={disabled}
                  aria-label={`${p.productName ?? ''} (${p.productCode ?? ''})`}
                  aria-current={current || undefined}
                  onClick={() => choose(p)}
                  className={cn(
                    'w-full rounded-md border px-3 py-2 text-left transition-colors hover:bg-hover active:duration-0',
                    current ? 'border-accent bg-surface' : 'border-border bg-surface',
                  )}
                >
                  <span className="flex items-baseline gap-2">
                    <span className="min-w-0 truncate text-sm font-medium">{p.productName}</span>
                    <span className="shrink-0 font-mono text-xs text-muted-foreground">{p.productCode}</span>
                    {needle && p.category && (
                      <span className="ml-auto shrink-0 text-xs text-muted-foreground">{humanizeStatus(p.category)}</span>
                    )}
                  </span>
                  <Description text={p.description} clamp className="mt-0.5" />
                </button>
              </li>
            );
          })}
        </ul>
      )}

      {selected && (
        <button
          type="button"
          onClick={() => {
            setBrowsing(false);
            setQuery('');
            setCategory(null);
          }}
          className="text-xs font-medium text-muted-foreground hover:text-foreground"
        >
          Keep {selected.productName}
        </button>
      )}
    </div>
  );
}

function Description({ text, clamp = false, className }: { text?: string | null | undefined; clamp?: boolean; className?: string }) {
  if (!text) {
    return <span className={cn('block text-xs text-muted-foreground', className)}>No description yet</span>;
  }
  return (
    <span className={cn('block text-xs text-muted-foreground', clamp && 'line-clamp-2', 'whitespace-pre-line', className)}>
      {text}
    </span>
  );
}
