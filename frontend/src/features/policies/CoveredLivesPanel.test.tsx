import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as funeralApi from '@/api/funeral';
import type { CoveredLifeView } from '@/api/types';
import { CoveredLivesPanel } from './CoveredLivesPanel';

vi.mock('@/api/funeral');

const life = (over: Partial<CoveredLifeView>): CoveredLifeView => ({
  coveredLifeId: crypto.randomUUID(),
  role: 'CHILD',
  fullName: 'Neema',
  dateOfBirth: '2016-01-01',
  sex: null,
  idNumber: null,
  student: false,
  partyId: null,
  benefit: 1000000,
  yearlyPremium: 6000,
  pricedAtAge: 10,
  coverStart: '2026-10-04',
  waitingPeriodEnds: '2027-04-04',
  coverEnd: null,
  status: 'ACTIVE',
  endReason: null,
  endedOn: null,
  ...over,
});

const family = [
  life({ role: 'MAIN_MEMBER', fullName: 'Juma', dateOfBirth: '1986-01-01', partyId: 'p-1', benefit: 2000000, yearlyPremium: 60000 }),
  life({ role: 'SPOUSE', fullName: 'Asha', dateOfBirth: '1988-01-01', benefit: 2000000, yearlyPremium: 60000 }),
  life({ fullName: 'Neema' }),
  life({ fullName: 'Baraka', status: 'ENDED', endReason: 'DECEASED', endedOn: '2026-12-01' }),
];

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(funeralApi.getCoveredLives).mockResolvedValue(family);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('CoveredLivesPanel', () => {
  it('lists every life with its waiting period and how it ended', async () => {
    render(<CoveredLivesPanel policyNumber="POL-1" canChange={false} />);
    const table = await screen.findByRole('table', { name: 'Covered lives' });
    expect(table).toHaveTextContent('Juma');
    expect(table).toHaveTextContent('Neema');
    expect(table).toHaveTextContent('Apr 4, 2027');
    expect(table).toHaveTextContent('Deceased');
    // An agent reads the family; only staff change it.
    expect(screen.queryByRole('button', { name: 'Add life' })).not.toBeInTheDocument();
  });

  it('never offers to remove the main member, and removes a dependant with a reason', async () => {
    vi.mocked(funeralApi.removeCoveredLife).mockResolvedValue(family[1]);
    render(<CoveredLivesPanel policyNumber="POL-1" canChange />);
    await screen.findByRole('table', { name: 'Covered lives' });
    expect(screen.queryByRole('button', { name: 'Remove life Juma' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove life Baraka' })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Remove life Asha' }));
    await userEvent.type(screen.getByLabelText('Reason for removal'), 'Divorce');
    await userEvent.click(screen.getByRole('button', { name: 'Confirm removal' }));

    await waitFor(() => expect(funeralApi.removeCoveredLife).toHaveBeenCalledWith('POL-1', family[1].coveredLifeId, 'Divorce'));
    expect(funeralApi.getCoveredLives).toHaveBeenCalledTimes(2);
  });

  it('offers the takeover once the main member has died and the spouse is still covered', async () => {
    vi.mocked(funeralApi.getCoveredLives).mockResolvedValue([
      { ...family[0], status: 'ENDED', endReason: 'DECEASED', endedOn: '2026-12-01' },
      family[1],
    ]);
    render(<CoveredLivesPanel policyNumber="POL-1" canChange />);
    expect(await screen.findByRole('button', { name: 'Complete takeover' })).toBeInTheDocument();
  });

  it('shows the refusal in the server words when a life cannot be added', async () => {
    vi.mocked(funeralApi.addCoveredLife).mockRejectedValue({
      kind: 'conflict', status: 409, title: 'Conflict', detail: 'At most 6 children may be covered', traceId: 't',
    });
    render(<CoveredLivesPanel policyNumber="POL-1" canChange />);
    await screen.findByRole('table', { name: 'Covered lives' });
    await userEvent.click(screen.getByRole('button', { name: 'Add life' }));
    expect(screen.getByLabelText('Full name')).toBeInTheDocument();
  });
});
