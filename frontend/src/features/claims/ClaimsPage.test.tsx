import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as claimsApi from '@/api/claims';
import { idle } from '@/store/createResourceSlice';
import { useClaimStore } from '@/store/claimStore';
import { grantAccess } from '@/test/access';
import { ClaimsPage } from './ClaimsPage';

vi.mock('@/api/claims', async (original) => ({
  ...(await original<typeof import('@/api/claims')>()),
  searchClaims: vi.fn(),
}));
vi.mock('@/api/products');

beforeEach(() => {
  vi.clearAllMocks();
  grantAccess('claims.view');
  useClaimStore.setState({ list: idle() });
  vi.mocked(claimsApi.searchClaims).mockResolvedValue({ items: [], page: { page: 0, pageSize: 20, totalElements: 0 } });
});

describe('the claims list', () => {
  /** A dashboard card's dates arrive and stay (2026-11-11, analytics plan 2). */
  it('lists only the claims registered in the card months, and can clear them', async () => {
    render(
      <MemoryRouter initialEntries={['/staff/claims?registeredFrom=2026-07-01&registeredTo=2026-09-30']}>
        <Routes><Route path="/staff/claims" element={<ClaimsPage />} /></Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText('Registered Jul 1, 2026 to Sep 30, 2026')).toBeInTheDocument();
    expect(claimsApi.searchClaims).toHaveBeenCalledWith(
      expect.objectContaining({ registeredFrom: '2026-07-01', registeredTo: '2026-09-30' }),
    );

    await userEvent.click(screen.getByRole('button', { name: 'Clear the registered dates' }));
    expect(claimsApi.searchClaims).toHaveBeenLastCalledWith(
      expect.not.objectContaining({ registeredFrom: expect.anything() }),
    );
  });
});
