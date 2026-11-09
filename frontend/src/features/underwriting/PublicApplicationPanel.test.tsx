import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as publicApi from '@/api/publicQuote';
import { PublicApplicationPanel } from './PublicApplicationPanel';

vi.mock('@/api/publicQuote');

beforeEach(() => vi.clearAllMocks());

const applied = {
  caseId: 'case-1', phoneNumber: '+255713555202', consentVersion: 'PUBLIC-APPLY-1', consentText: 'Before you send…',
  language: 'sw' as const, appliedAt: '2026-11-08T10:00:00Z', possibleExistingClients: [],
};

/** 2026-11-02, the user: an existing client is never merged -- staff see a flag. */
describe('a case from the public quote link', () => {
  it('flags anyone who may already be a client, with a link to them', async () => {
    vi.mocked(publicApi.getCasePublicApplication).mockResolvedValue({
      ...applied, possibleExistingClients: [{ partyId: 'p-9', displayName: 'Rehema Said' }],
    });
    render(<MemoryRouter><PublicApplicationPanel caseId="case-1" /></MemoryRouter>);

    expect(await screen.findByText('Possible existing client')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Rehema Said' })).toHaveAttribute('href', '/staff/parties/p-9');
    expect(screen.getByText('+255713555202')).toBeInTheDocument();
    expect(screen.getByText('Agreed, read in Swahili')).toBeInTheDocument();
  });

  it('raises no flag when nobody matches', async () => {
    vi.mocked(publicApi.getCasePublicApplication).mockResolvedValue(applied);
    render(<MemoryRouter><PublicApplicationPanel caseId="case-1" /></MemoryRouter>);

    expect(await screen.findByText('+255713555202')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
