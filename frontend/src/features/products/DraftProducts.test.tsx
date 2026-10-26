import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { grantRoles } from '@/test/access';
import type { ProductSummary } from '@/api/types';
import { idle, success } from '@/store/createResourceSlice';
import { useProductStore } from '@/store/productStore';
import { ProductsPage } from './ProductsPage';

/**
 * Draft products on the products page -- a view of their own since 2026-10-22.
 *
 * Authoring is two phases and only the second flips DRAFT to ACTIVE; `GET /products` returns ACTIVE only, so an
 * abandoned second phase left a product in no list while `ux_product_code` held its code. `GET /products/drafts`
 * closes that. These hold the rules: drafts are listed with the way back into their setup, they are counted where the
 * author will see it, an empty view says so, and a non-ADMIN never sees or requests them.
 */

let roles: string[] = [];

// `canAuthorProducts` reads realm_access.roles off the access token, stubbed at the auth boundary.
vi.mock('react-oidc-context', () => ({
  useAuth: () => ({
    user: roles.length
      ? { access_token: `x.${btoa(JSON.stringify({ realm_access: { roles } }))}.y` }
      : undefined,
  }),
}));

const activeProduct = {
  productId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  productCode: 'DEMO-TERM-01',
  productName: 'Demo Term Life',
  category: 'TERM_LIFE',
  status: 'ACTIVE',
  defaultCurrency: 'TZS',
} as ProductSummary;

const draft = {
  productId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  productCode: 'Education02',
  productName: 'Education Plan',
  category: 'EDUCATION_SAVINGS',
  status: 'DRAFT',
  defaultCurrency: 'TZS',
} as ProductSummary;

const loadDrafts = vi.fn(async () => {});

function renderPage(drafts: ProductSummary[] | null, path = '/staff/products') {
  useProductStore.setState({
    list: success([activeProduct]),
    drafts: drafts === null ? idle() : success(drafts),
    snapshots: {},
    ratings: {},
    creating: idle(),
    publishing: {},
    loadList: vi.fn(async () => {}),
    loadDrafts,
    loadSnapshot: vi.fn(async () => {}),
    loadRating: vi.fn(async () => {}),
  });

  // On its real route: the "Continue setup" link is RELATIVE, so without the route it would resolve against "/".
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/staff/products" element={<ProductsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('draft products', () => {
  beforeEach(() => {
    roles = [];

    grantRoles(...roles);
    loadDrafts.mockClear();
    useProductStore.setState({ drafts: idle() });
  });

  it('lists an unpublished product with its code and the way back into its setup', () => {
    roles = ['ADMIN'];

    grantRoles(...roles);
    renderPage([draft], '/staff/products?view=drafts');

    expect(screen.getByRole('table', { name: 'Draft products' })).toBeInTheDocument();
    expect(screen.getByText('Education Plan')).toBeInTheDocument();
    // The code, because "already exists" is the message a reader arrives holding.
    expect(screen.getByText('Education02')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Continue setup/ })).toHaveAttribute(
      'href',
      '/staff/products/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    );
  });

  it('counts the drafts on their tab, where the live catalogue is showing', () => {
    roles = ['ADMIN'];

    grantRoles(...roles);
    renderPage([draft, { ...draft, productId: 'cccccccc-cccc-cccc-cccc-cccccccccccc', productCode: 'Edu01' }]);
    expect(screen.getByRole('button', { name: /Drafts/ })).toHaveTextContent('2');
    expect(screen.getByText('Demo Term Life')).toBeInTheDocument();
  });

  it('says so when nothing is unfinished', () => {
    roles = ['ADMIN'];

    grantRoles(...roles);
    renderPage([], '/staff/products?view=drafts');
    expect(screen.getByText('No drafts')).toBeInTheDocument();
  });

  /**
   * The gate that matters: `GET /products/drafts` is ADMIN-only server-side, so a non-admin requesting it would park a
   * 403 in the store for a list nobody asked for. It must not even be requested -- not even from a drafts link.
   */
  it('is neither shown nor requested for a staff member who cannot author', () => {
    roles = ['FINANCE_OFFICER'];

    grantRoles(...roles);
    renderPage([draft], '/staff/products?view=drafts');

    expect(screen.queryByRole('button', { name: /Drafts/ })).not.toBeInTheDocument();
    expect(screen.queryByText('Education02')).not.toBeInTheDocument();
    expect(screen.getByText('Demo Term Life')).toBeInTheDocument();
    expect(loadDrafts).not.toHaveBeenCalled();
  });
});
