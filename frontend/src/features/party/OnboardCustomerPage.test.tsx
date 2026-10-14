import { act, screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { usePartyStore } from '@/store/partyStore';
import { renderScreen } from '@/test/renderScreen';
import { OnboardCustomerPage } from './OnboardCustomerPage';

vi.mock('@/api/party');

/**
 * What an agent does NEXT, which is the half this screen used to leave out.
 *
 * Registering a client and collecting their ID documents are one visit in the field: the agent
 * is holding the passport while they fill this form in. The screen ended at "Registered /
 * Register another", so the place to send those documents had to be found by going back to My
 * clients, searching for the person just registered, opening them and reading along a tab strip
 * -- which is why the upload path was reported as missing when it existed.
 */

const PARTY_ID = '22222222-2222-2222-2222-222222222222';

/**
 * Seeded AFTER mount, never before: the form resets this slot in a mount effect, so a fixture
 * set in `beforeEach` is wiped before the first assertion and the screen renders the empty form.
 */
function renderRegistered(route: string, path: string) {
  const rendered = renderScreen(
    /*
     * Nested the way `App.tsx` nests it -- a realm route with the screen as a flat child --
     * because that nesting is the whole subject of the assertion. Route-relative `..` climbs
     * one ROUTE, not one URL segment, so a flat `<Route path="/agents/customers/new">` would
     * resolve `..` to the document root and this test would assert against a structure the app
     * does not have.
     */
    <Routes>
      <Route path="/agents">
        <Route path={path} element={<OnboardCustomerPage />} />
      </Route>
    </Routes>,
    { route },
  );
  act(() => {
    usePartyStore.setState({
      registeringIndividual: {
        status: 'success',
        error: null,
        loadedAt: Date.now(),
        data: { partyId: PARTY_ID, partyType: 'INDIVIDUAL', kycStatus: 'PENDING', displayName: 'Amina Owner' },
      },
    } as never);
  });
  return rendered;
}

describe('OnboardCustomerPage', () => {
  /**
   * Route-relative, so `..` is the realm however many segments this screen's own path has --
   * the bug ClientsPage's row activation already records once, where a path-relative `..` sent
   * every row click to the realm picker.
   */
  it('sends the agent straight to the new client KYC tab', () => {
    renderRegistered('/agents/customers/new', 'customers/new');

    const link = screen.getByRole('link', { name: 'Send identity documents' });
    expect(link).toHaveAttribute('href', `/agents/parties/${PARTY_ID}?tab=kyc`);
  });

  it('still offers registering another, and does not make that the primary act', () => {
    renderRegistered('/agents/customers/new', 'customers/new');

    expect(screen.getByRole('button', { name: 'Register another' })).toBeInTheDocument();
  });
});
