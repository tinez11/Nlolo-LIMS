import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as productApi from '@/api/products';
import type { ProductSummary } from '@/api/types';
import { ProductName, __clearProductNameCache } from './ProductName';

vi.mock('@/api/products');

/**
 * Resolving a product id to a product name.
 *
 * ## What this is defending
 *
 * No read on this platform turned a product id into a NAME: the active-snapshot
 * lookup takes an id but returns pricing, and the catalogue carries names while
 * being keyed by nothing (and holding ACTIVE only). So every screen referencing a
 * product it had not itself picked from a list printed the uuid -- the
 * underwriting queue rendered a column of them, and the case detail rail printed
 * one under a note admitting no lookup existed.
 *
 * Three rules matter here. It shows the name. It falls back to the id rather than
 * to nothing or to an invented placeholder, because a 404 from `GET
 * /products/{id}` is not proof of absence. And it costs one request per distinct
 * product however many rows reference it -- the queue argument, which is the
 * whole reason the cache is module state.
 */

const demoTermLife: ProductSummary = {
  productId: '3e5f14e4-9f32-490b-854d-35271877be83',
  productCode: 'DEMO-TERM-01',
  productName: 'Demo Term Life',
  category: 'TERM_LIFE',
  status: 'ACTIVE',
  defaultCurrency: 'TZS',
};

beforeEach(() => {
  vi.clearAllMocks();
  // The cache is module state, so without this a spec inherits the previous
  // one's answers and stops testing its own stub.
  __clearProductNameCache();
});

describe('ProductName', () => {
  it('renders the name and the code once resolved', async () => {
    vi.mocked(productApi.getProduct).mockResolvedValue(demoTermLife);

    render(<ProductName productId={demoTermLife.productId!} />);

    expect(await screen.findByText('Demo Term Life')).toBeVisible();
    expect(screen.getByText('DEMO-TERM-01')).toBeVisible();
  });

  it('keeps the id reachable on the title, for a support thread', async () => {
    vi.mocked(productApi.getProduct).mockResolvedValue(demoTermLife);

    render(<ProductName productId={demoTermLife.productId!} />);

    expect(await screen.findByTitle(demoTermLife.productId!)).toBeInTheDocument();
  });

  it('drops the code when asked, for a column that is scanned', async () => {
    vi.mocked(productApi.getProduct).mockResolvedValue(demoTermLife);

    render(<ProductName productId={demoTermLife.productId!} withCode={false} />);

    expect(await screen.findByText('Demo Term Life')).toBeVisible();
    expect(screen.queryByText('DEMO-TERM-01')).not.toBeInTheDocument();
  });

  it('shows the raw id when the lookup fails, not nothing and not a placeholder', async () => {
    vi.mocked(productApi.getProduct).mockRejectedValue(new Error('404'));
    const id = '3e5f14e4-9f32-490b-854d-35271877be83';

    render(<ProductName productId={id} />);

    expect(screen.getByText(id)).toBeVisible();
    await waitFor(() => expect(productApi.getProduct).toHaveBeenCalledTimes(1));
    expect(screen.getByText(id)).toBeVisible();
  });

  it('costs one request for twenty rows of the same product', async () => {
    vi.mocked(productApi.getProduct).mockResolvedValue(demoTermLife);

    render(
      <>
        {Array.from({ length: 20 }, (_, i) => (
          <ProductName key={i} productId={demoTermLife.productId!} />
        ))}
      </>,
    );

    await waitFor(() => expect(screen.getAllByText('Demo Term Life')).toHaveLength(20));
    expect(productApi.getProduct).toHaveBeenCalledTimes(1);
  });

  it('does not retry an id it has already failed to resolve', async () => {
    vi.mocked(productApi.getProduct).mockRejectedValue(new Error('403'));
    const id = '3e5f14e4-9f32-490b-854d-35271877be83';

    const { unmount } = render(<ProductName productId={id} />);
    await waitFor(() => expect(productApi.getProduct).toHaveBeenCalledTimes(1));
    unmount();

    render(<ProductName productId={id} />);
    await waitFor(() => expect(screen.getByText(id)).toBeVisible());
    expect(productApi.getProduct).toHaveBeenCalledTimes(1);
  });
});
