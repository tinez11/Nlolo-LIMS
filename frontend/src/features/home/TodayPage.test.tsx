import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorkWaiting } from '@/navBadges';
import { TodayPage } from './TodayPage';

vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { access_token: 't' } }) }));

let work: { loading: boolean; rows: WorkWaiting[] } = { loading: true, rows: [] };
vi.mock('@/navBadges', () => ({ useWorkWaiting: () => work }));

const row = (key: WorkWaiting['key'], count: number, title: string, to: string): WorkWaiting => ({
  key,
  count,
  title,
  to,
});

function renderPage() {
  render(
    <MemoryRouter>
      <TodayPage />
    </MemoryRouter>,
  );
}

describe('TodayPage', () => {
  beforeEach(() => {
    work = { loading: true, rows: [] };
  });

  it('says it is counting while it counts', () => {
    renderPage();
    expect(screen.getByRole('status')).toHaveTextContent('Counting what is waiting');
  });

  it('lists each queue with work in it, opening the list filtered to that work', () => {
    work = {
      loading: false,
      rows: [
        row('claims-unassessed', 12, '12 claims registered and not yet assessed', 'claims?status=REGISTERED'),
        row('eft-awaiting', 0, '0 bank transfers nobody has made yet', 'bank-transfers'),
      ],
    };
    renderPage();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('claims registered and not yet assessed')).toBeInTheDocument();
    // An empty queue is not listed: the page is what is waiting.
    expect(screen.queryByText(/bank transfers/)).not.toBeInTheDocument();
    const open = screen.getByRole('link', { name: 'Open' });
    expect(open).toHaveAttribute('href', '/staff/claims?status=REGISTERED');
    expect(open).toHaveAccessibleDescription('claims registered and not yet assessed');
  });

  it('says so plainly when nothing is waiting', () => {
    work = { loading: false, rows: [row('claims-unassessed', 0, '0 claims registered and not yet assessed', 'claims')] };
    renderPage();
    expect(screen.getByText('Nothing waiting for you')).toBeInTheDocument();
    expect(screen.getByText('Every queue you work is empty.')).toBeInTheDocument();
  });

  // A failed count is left out by the hook; with every count failed there is nothing to claim.
  it('does not say "empty" when it could not count at all', () => {
    work = { loading: false, rows: [] };
    renderPage();
    expect(screen.getByText('None of your queues could be counted just now.')).toBeInTheDocument();
  });
});
