import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import * as portalApi from '@/api/portal';
import type { CustomerProduct } from '@/api/portal';
import { CustomerProductsPage } from './CustomerProductsPage';

vi.mock('@/api/portal');

const term: CustomerProduct = {
  productId: 'p-term', productName: 'Online Term Cover', category: 'TERM_LIFE', currency: 'TZS',
  summary: 'Cover for your family if you die', benefits: ['Pays the sum assured on death'], quotable: true,
};
const funeral: CustomerProduct = {
  productId: 'p-fun', productName: 'Nuru Funeral Cover', category: 'FUNERAL', currency: 'TZS',
  summary: 'Funeral costs for your family', benefits: [], quotable: false,
};

function renderShelf() {
  render(
    <MemoryRouter initialEntries={['/customers/products']}>
      <Routes>
        <Route path="/customers/products" element={<CustomerProductsPage />} />
        <Route path="/customers/applications" element={<p>applications</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('CustomerProductsPage', () => {
  it('prices a product on the customer and asks for it with that cover', async () => {
    vi.mocked(portalApi.getCustomerProducts).mockResolvedValue([term, funeral]);
    vi.mocked(portalApi.quoteCustomerProduct).mockResolvedValue({ productId: 'p-term', productName: 'Online Term Cover',
      currency: 'TZS', sumAssured: 10000000, frequency: 'MONTHLY', instalment: 8333.33, yearly: 100000, ageAtEntry: 30 });
    vi.mocked(portalApi.applyForProduct).mockResolvedValue({} as never);
    renderShelf();

    expect(await screen.findByText('Cover for your family if you die')).toBeInTheDocument();
    // Priced by an adviser: asked for straight away.
    expect(screen.getByText(/an adviser will contact you/)).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('Cover (TZS)'), '10,000,000');
    await userEvent.click(screen.getByRole('button', { name: 'Get a price' }));
    expect(await screen.findByRole('status')).toHaveTextContent('TZS 8,333.33');

    const asks = screen.getAllByRole('button', { name: 'Ask for this cover' });
    await userEvent.click(asks[0] as HTMLElement);
    expect(await screen.findByText('applications')).toBeInTheDocument();
    expect(portalApi.applyForProduct).toHaveBeenCalledWith({ productId: 'p-term', sumAssured: 10000000, frequency: 'MONTHLY' });
  });
});
