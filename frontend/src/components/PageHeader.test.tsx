import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { PageHeader } from './PageHeader';

describe('PageHeader', () => {
  it('keeps the title as the one h1, and renders a breadcrumb trail as navigation', () => {
    render(
      <MemoryRouter>
        <PageHeader
          title="POL-123"
          breadcrumb={[{ label: 'Policies', to: '/staff/policies' }]}
          status={<span>Active</span>}
        />
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { level: 1, name: 'POL-123' })).toBeInTheDocument();
    const trail = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(trail).toContainElement(screen.getByRole('link', { name: 'Policies' }));
    expect(screen.getByText('Active')).toBeInTheDocument();
  });

  it('is sticky', () => {
    const { container } = render(
      <MemoryRouter>
        <PageHeader title="Policies" />
      </MemoryRouter>,
    );
    expect(container.firstElementChild).toHaveClass('sticky', 'top-0');
  });

  it('still renders a plain title page with no breadcrumb or status', () => {
    render(
      <MemoryRouter>
        <PageHeader title="Policies" description="Every policy in your tenant." count="666" />
      </MemoryRouter>,
    );
    expect(screen.queryByRole('navigation', { name: 'Breadcrumb' })).not.toBeInTheDocument();
    expect(screen.getByText('Every policy in your tenant.')).toBeInTheDocument();
    expect(screen.getByText('666')).toBeInTheDocument();
  });
});
