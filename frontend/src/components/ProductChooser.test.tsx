import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { ProductSummary } from '@/api/types';
import { ProductChooser } from './ProductChooser';
import { ProductFilter } from './ProductFilter';

const product = (id: string, name: string, code: string, category: ProductSummary['category'], description: string | null = null) =>
  ({ productId: id, productName: name, productCode: code, category, description }) as ProductSummary;

const PRODUCTS = [
  product('t1', 'Term Shield', 'TERM-01', 'TERM_LIFE', 'Pure cover for 5 to 20 years.'),
  product('t2', 'Term Plus', 'TERM-02', 'TERM_LIFE'),
  product('f1', 'Family Funeral', 'FUN-01', 'FUNERAL', 'Pays the funeral within 48 hours.'),
  product('g1', 'Staff Group Life', 'GRP-01', 'GROUP_LIFE'),
];

function Harness({ allow, initial = '' }: { allow?: (p: ProductSummary) => boolean; initial?: string }) {
  const [value, setValue] = useState(initial);
  return <ProductChooser products={PRODUCTS} allow={allow} value={value} onChange={(id) => setValue(id)} />;
}

describe('ProductChooser', () => {
  it('asks for a category first, then lists that category with each description', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    expect(screen.getByText('Choose a category to see its products.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Term Shield (TERM-01)' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Term life/ }));
    expect(screen.getByRole('button', { name: 'Term Shield (TERM-01)' })).toHaveTextContent('Pure cover for 5 to 20 years.');
    expect(screen.getByRole('button', { name: 'Term Plus (TERM-02)' })).toHaveTextContent('No description yet');
    expect(screen.queryByRole('button', { name: 'Family Funeral (FUN-01)' })).not.toBeInTheDocument();
  });

  it('offers only the categories the screen allows, with their counts', () => {
    render(<Harness allow={(p) => p.category !== 'GROUP_LIFE'} />);
    const group = screen.getByRole('group', { name: 'Product category' });
    expect(group).toHaveTextContent('Term life2');
    expect(group).toHaveTextContent('Funeral1');
    expect(group).not.toHaveTextContent('Group life');
  });

  it('shows the products straight away when the screen offers one category', () => {
    render(<Harness allow={(p) => p.category === 'TERM_LIFE'} />);
    expect(screen.queryByRole('group', { name: 'Product category' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Term Plus (TERM-02)' })).toBeInTheDocument();
  });

  it('searches every category at once', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.type(screen.getByRole('searchbox', { name: 'Find a product' }), 'fun-01');
    expect(screen.getByRole('button', { name: 'Family Funeral (FUN-01)' })).toBeInTheDocument();
  });

  it('collapses to the chosen product, and Change keeps it until another is picked', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<ProductChooser products={PRODUCTS} value="f1" onChange={onChange} />);
    expect(screen.getByText('Family Funeral')).toBeInTheDocument();
    expect(screen.getByText('Pays the funeral within 48 hours.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Change product/ }));
    // Reopens on the chosen product's own category, marked as the current one.
    expect(screen.getByRole('button', { name: 'Family Funeral (FUN-01)' })).toHaveAttribute('aria-current', 'true');
    await user.click(screen.getByRole('button', { name: 'Keep Family Funeral' }));
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /Change product/ })).toBeInTheDocument();
  });
});

describe('ProductFilter', () => {
  it('narrows the products to the chosen category and drops a product outside it', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<ProductFilter products={PRODUCTS} category="" productId="t1" onChange={onChange} />);
    // A product alone implies its category.
    expect(screen.getByRole('combobox', { name: 'Product category' })).toHaveValue('TERM_LIFE');
    expect(screen.getAllByRole('option').map((o) => o.textContent)).not.toContain('Family Funeral');

    await user.selectOptions(screen.getByRole('combobox', { name: 'Product category' }), 'FUNERAL');
    expect(onChange).toHaveBeenCalledWith({ category: 'FUNERAL', productId: '' });
  });
});
