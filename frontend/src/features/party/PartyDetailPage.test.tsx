import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PartyDetailView } from '@/api/types';
import { usePartyStore } from '@/store/partyStore';
import { useClientRecordStore } from '@/store/clientRecord';
import { renderScreen } from '@/test/renderScreen';
import { PartyDetailPage } from './PartyDetailPage';

vi.mock('@/api/party');
vi.mock('@/api/policies');
vi.mock('@/api/claims');
vi.mock('@/api/underwriting');
vi.mock('@/api/distribution');

/**
 * Written BEFORE the party record is restructured into tabs, which is the order plan 4
 * established and this file exists to repeat: the tests that pass identically either side of a
 * restructure are what make it a restructure rather than a rewrite.
 *
 * At 1181 lines this is the largest screen on the platform, and the one the agreed proposal
 * named for tabs back on 2026-09-25 -- so it has the most to lose from a move made blind.
 */

const PARTY_ID = '11111111-1111-1111-1111-111111111111';

const RESOURCE = { status: 'success' as const, error: null, loadedAt: Date.now() };

function party(over: Partial<PartyDetailView> = {}): PartyDetailView {
  return {
    partyId: PARTY_ID,
    partyType: 'INDIVIDUAL',
    kycStatus: 'PENDING',
    displayName: 'Amina Owner',
    dateOfBirth: '1985-01-01',
    phoneNumber: '+255700000001',
    ...over,
  };
}

/**
 * Routed rather than mocking `useParams`. The page reads its id from the URL, so a test that
 * stubs the hook is testing a different component from the one that ships -- and a `vi.mock`
 * factory is hoisted above the constants it would need, which is how the first attempt at this
 * file failed before a single assertion ran.
 */
function renderAt(route: string, realm: 'staff' | 'agents' = 'staff') {
  const path = realm === 'staff' ? '/staff/parties/:partyId' : '/agents/clients/:partyId';
  return renderScreen(
    <Routes>
      <Route path={path} element={<PartyDetailPage realm={realm} />} />
    </Routes>,
    { route },
  );
}

/** Every register this record reads, empty. A test that wants one fills it in. */
function emptyRegisters() {
  const page = { page: { page: 0, pageSize: 20, totalElements: 0 }, items: [] };
  useClientRecordStore.setState({
    policies: { [PARTY_ID]: { ...RESOURCE, data: page } },
    claims: { [PARTY_ID]: { ...RESOURCE, data: page } },
    cases: { [PARTY_ID]: { ...RESOURCE, data: page } },
  } as never);
}

beforeEach(() => {
  usePartyStore.setState({
    detail: { [PARTY_ID]: { ...RESOURCE, data: party() } },
    documents: { [PARTY_ID]: { ...RESOURCE, data: [] } },
  } as never);
  emptyRegisters();
});

describe('PartyDetailPage', () => {
  it('renders the record with the party the store holds', () => {
    renderAt(`/staff/parties/${PARTY_ID}`);
    expect(screen.getByRole('heading', { name: 'Amina Owner' })).toBeInTheDocument();
  });

  /**
   * The rail is the identity, and it is what a restructure is most likely to move by accident:
   * these are the facts that must stay on screen while somebody works in the other column.
   */
  it('keeps who the person is in the record rail', () => {
    renderAt(`/staff/parties/${PARTY_ID}`);
    expect(screen.getByRole('heading', { level: 2, name: 'Identity' })).toBeInTheDocument();
    expect(screen.getByText('+255700000001')).toBeInTheDocument();
  });

  /**
   * THE GATE THAT MATTERS. KYC verification is a staff act -- an agent looking at their own
   * client must not be offered the decision, and `GET /agents` is staff-only besides, which is
   * why the page does not even fetch the agent record in the agents realm.
   */
  it('offers KYC verification to staff', () => {
    renderAt(`/staff/parties/${PARTY_ID}`, 'staff');
    expect(screen.getByRole('heading', { level: 2, name: 'KYC verification' })).toBeInTheDocument();
  });

  it('offers it to nobody in the agents realm', () => {
    renderAt(`/agents/clients/${PARTY_ID}`, 'agents');
    expect(screen.queryByRole('heading', { level: 2, name: 'KYC verification' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 2, name: 'Also an agent' })).not.toBeInTheDocument();
  });

  /**
   * An agent sees their own book, and the wording says so. "Your Policies" is not a cosmetic
   * difference from "Policies": a policy written by another agent does not appear, and the
   * subtitle is where the console admits that.
   */
  it('names the register from the reader it is for', () => {
    renderAt(`/agents/clients/${PARTY_ID}`, 'agents');
    expect(screen.getByRole('heading', { level: 2, name: 'Your Policies' })).toBeInTheDocument();
  });

  /**
   * A corporate has no date of birth, sex or smoker status, so the Person panel is withheld
   * rather than rendered as a column of em dashes -- which would read as data the platform lost
   * instead of data it never asked for. The page's own comment says exactly this.
   */
  it('withholds the underwriting-facts panel from a corporate', () => {
    usePartyStore.setState({
      detail: {
        [PARTY_ID]: {
          ...RESOURCE,
          data: party({ partyType: 'CORPORATE', dateOfBirth: null, registrationNumber: 'RC-1' }),
        },
      },
    } as never);
    renderAt(`/staff/parties/${PARTY_ID}`);
    expect(screen.queryByRole('heading', { level: 2, name: 'Person' })).not.toBeInTheDocument();
  });
});
