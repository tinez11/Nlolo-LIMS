import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { cameFrom, useBreadcrumb, type Crumb } from './cameFrom';

const OWN: Crumb[] = [{ label: 'Policies', to: '/staff/policies' }];

function Crumbs() {
  return <p>{useBreadcrumb(OWN).map((c) => `${c.label}=${c.to}`).join(' > ')}</p>;
}

const at = (state: unknown) =>
  render(
    <MemoryRouter initialEntries={[{ pathname: '/staff/policies/POL-1', state }]}>
      <Crumbs />
    </MemoryRouter>,
  );

describe('useBreadcrumb', () => {
  it('leads back to the client a record was opened from', () => {
    at(cameFrom([
      { label: 'Individuals', to: '/staff/clients/individuals' },
      { label: 'Amina Owner', to: '/staff/parties/p-1' },
    ]));
    expect(screen.getByText('Individuals=/staff/clients/individuals > Amina Owner=/staff/parties/p-1')).toBeInTheDocument();
  });

  it('keeps the page own crumb when opened any other way', () => {
    at(null);
    expect(screen.getByText('Policies=/staff/policies')).toBeInTheDocument();
  });

  it('ignores an empty trail', () => {
    at({ cameFrom: [] });
    expect(screen.getByText('Policies=/staff/policies')).toBeInTheDocument();
  });

  it('ignores a crumb that is not a path inside the console', () => {
    at({ cameFrom: [{ label: 'Elsewhere', to: 'https://example.com' }] });
    expect(screen.getByText('Policies=/staff/policies')).toBeInTheDocument();
  });
});
