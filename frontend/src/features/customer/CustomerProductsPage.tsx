import { Check } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  applyForProduct,
  getCustomerProducts,
  quoteCustomerProduct,
  type CustomerProduct,
  type CustomerQuote,
} from '@/api/portal';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';
import { categoryText, money, perFrequency } from './customerText';

type Frequency = 'MONTHLY' | 'QUARTERLY' | 'ANNUALLY';

/**
 * Products offered online (2026-10-08, the customer portal design step 5; PRD §13-16): what each is for, a price on
 * the customer's own details where the portal can work one out, and a way to ask for it. Asking opens an application
 * that our team and underwriting decide; nothing here is an offer.
 */
export function CustomerProductsPage() {
  const [products, setProducts] = useState<CustomerProduct[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let live = true;
    getCustomerProducts().then(
      (p) => { if (live) { setProducts(p); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [reload]);

  return (
    <>
      <PageHeader title="Products" description="Cover you can price and ask for online. Prices here are a guide, not an offer." />
      <div className="space-y-4 px-4 pb-8 sm:px-6">
        {error ? (
          <ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} />
        ) : !products ? (
          <LoadingBlock label="Loading products" />
        ) : products.length === 0 ? (
          <EmptyState title="Nothing offered online yet" description="Contact us or your agent about cover." />
        ) : (
          products.map((p) => <ProductCard key={p.productId} product={p} />)
        )}
      </div>
    </>
  );
}

function ProductCard({ product }: { product: CustomerProduct }) {
  const navigate = useNavigate();
  const [amount, setAmount] = useState('');
  const [frequency, setFrequency] = useState<Frequency>('MONTHLY');
  const [quote, setQuote] = useState<CustomerQuote | null>(null);
  const [busy, setBusy] = useState<'quote' | 'apply' | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const sumAssured = Number(amount.replace(/,/g, ''));
  const validAmount = amount.trim() !== '' && Number.isFinite(sumAssured) && sumAssured > 0;

  async function price() {
    setBusy('quote');
    setError(null);
    try {
      setQuote(await quoteCustomerProduct(product.productId, { sumAssured, frequency }));
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(null);
    }
  }

  async function ask() {
    setBusy('apply');
    setError(null);
    try {
      await applyForProduct({ productId: product.productId, sumAssured: validAmount ? sumAssured : null,
        frequency: product.quotable ? frequency : null });
      navigate('/customers/applications');
    } catch (e) {
      setError(toApiError(e));
      setBusy(null);
    }
  }

  return (
    <Panel title={product.productName} subtitle={categoryText(product.category)}>
      <div className="space-y-3 px-4 pb-4">
        {product.summary && <p className="text-sm">{product.summary}</p>}
        {product.benefits.length > 0 && (
          <ul className="space-y-1">
            {product.benefits.map((b) => (
              <li key={b} className="flex gap-2 text-sm">
                <Check className="mt-0.5 size-4 shrink-0 text-status-success-fg" aria-hidden />{b}
              </li>
            ))}
          </ul>
        )}
        {product.quotable ? (
          <div className="flex flex-wrap items-end gap-2">
            <FormField label={`Cover (${product.currency})`} className="min-w-40 flex-1">
              <Input inputMode="numeric" value={amount} placeholder="e.g. 10,000,000"
                onChange={(e) => { setAmount(e.target.value); setQuote(null); }} />
            </FormField>
            <FormField label="Pay" className="min-w-32">
              <Select value={frequency} onChange={(e) => { setFrequency(e.target.value as Frequency); setQuote(null); }}>
                <option value="MONTHLY">Monthly</option>
                <option value="QUARTERLY">Quarterly</option>
                <option value="ANNUALLY">Yearly</option>
              </Select>
            </FormField>
            <Button disabled={!validAmount} pending={busy === 'quote'} onClick={() => void price()}>Get a price</Button>
          </div>
        ) : (
          <p className="text-xs text-muted-foreground">Ask for it and an adviser will contact you with a price.</p>
        )}
        {quote && (
          <p className="rounded-md bg-control px-3 py-2 text-sm" role="status">
            About <span className="font-semibold tabular-nums">{money(quote.instalment, quote.currency)}</span>{' '}
            {perFrequency(quote.frequency)} for {money(quote.sumAssured, quote.currency)} of cover, at age {quote.ageAtEntry}.
            <span className="block text-xs text-muted-foreground">A guide: the final price is set when we review your application.</span>
          </p>
        )}
        {error && <InlineError error={error} lead={busy === 'apply' ? 'Not asked for' : 'Could not do that'} />}
        {(quote || !product.quotable) && (
          <Button variant="primary" pending={busy === 'apply'} onClick={() => void ask()}>Ask for this cover</Button>
        )}
      </div>
    </Panel>
  );
}
