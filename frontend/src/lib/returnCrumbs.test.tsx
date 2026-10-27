import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { fromList, useReturnCrumbs } from './returnCrumbs';

const OWN = [{ label: 'Payouts', to: '/staff/payouts' }];
const at = (path: string) => ({ children }: { children: ReactNode }) => (
  <MemoryRouter initialEntries={[path]}>{children}</MemoryRouter>
);

describe('return crumbs', () => {
  it('leads back to the working list a record was opened from', () => {
    const { result } = renderHook(() => useReturnCrumbs(OWN), { wrapper: at('/staff/payouts/i-1?from=approvals') });
    expect(result.current).toEqual([{ label: 'Approvals', to: '/staff/approvals' }]);
  });

  it("keeps the page's own way back otherwise", () => {
    const { result } = renderHook(() => useReturnCrumbs(OWN), { wrapper: at('/staff/payouts/i-1') });
    expect(result.current).toEqual(OWN);
  });

  it('adds the origin to a link with or without a query', () => {
    expect(fromList('/staff/payouts/i-1', 'payments')).toBe('/staff/payouts/i-1?from=payments');
    expect(fromList('/staff/policies/P?tab=account', 'approvals')).toBe('/staff/policies/P?tab=account&from=approvals');
  });
});
