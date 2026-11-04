import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getActions, type ActionsView } from '@/api/actions';
import { renderScreen } from '@/test/renderScreen';
import { ActionBell } from './ActionBell';
import { actionLink, allLink, kindLabel, waitedFor } from './actionLinks';

vi.mock('@/api/actions', () => ({ getActions: vi.fn() }));

const twoHoursAgo = () => new Date(Date.now() - 2 * 3_600_000).toISOString();

const view = (over: Partial<ActionsView> = {}): ActionsView => ({
  total: 13,
  incomplete: false,
  groups: [
    { kind: 'proposal-returned', act: 'Correct it and resubmit', total: 7, items: [
      { reference: 'case-1', policyNumber: null, title: 'Peter Mushi', detail: 'Proposal PRO-0002 returned', since: twoHoursAgo() },
    ] },
    { kind: 'offer-closing', act: 'Remind the client to pay', total: 6, items: [
      { reference: 'POL-1', policyNumber: 'POL-1', title: 'Asha Mollel', detail: 'Offer POL-1 closes 6 Nov', since: null },
    ] },
  ],
  ...over,
});

beforeEach(() => vi.clearAllMocks());

function renderBell() {
  renderScreen(
    <Routes>
      <Route path="/agents/dashboard" element={<ActionBell realm="agents" />} />
      <Route path="/agents/proposals/:caseId" element={<p>The proposal page</p>} />
      <Route path="/agents/proposals" element={<p>Every proposal needing action</p>} />
    </Routes>,
    { route: '/agents/dashboard' },
  );
}

async function openBell(name: string) {
  await userEvent.click(await screen.findByRole('button', { name }));
}

describe('ActionBell', () => {
  it('shows one line per kind of work with its own count', async () => {
    vi.mocked(getActions).mockResolvedValue(view());
    renderBell();
    await openBell('13 jobs waiting for you');

    expect(await screen.findByRole('button', { name: /Proposals returned to you/ })).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByRole('button', { name: /Offers closing unpaid/ })).toBeInTheDocument();
    expect(screen.getByText('13 jobs')).toBeInTheDocument();
    // Collapsed: the person is not shown until the kind is opened.
    expect(screen.queryByText('Peter Mushi')).not.toBeInTheDocument();
  });

  it('opens a kind to list its jobs person first, and a job opens the page where it is done', async () => {
    vi.mocked(getActions).mockResolvedValue(view());
    renderBell();
    await openBell('13 jobs waiting for you');
    await userEvent.click(await screen.findByRole('button', { name: /Proposals returned to you/ }));

    expect(screen.getByText('Proposal PRO-0002 returned')).toBeInTheDocument();
    expect(screen.getByText('2 h ago')).toBeInTheDocument();
    await userEvent.click(screen.getByText('Peter Mushi'));
    expect(await screen.findByText('The proposal page')).toBeInTheDocument();
  });

  it('keeps one kind open at a time', async () => {
    vi.mocked(getActions).mockResolvedValue(view());
    renderBell();
    await openBell('13 jobs waiting for you');
    await userEvent.click(await screen.findByRole('button', { name: /Proposals returned to you/ }));
    await userEvent.click(screen.getByRole('button', { name: /Offers closing unpaid/ }));

    expect(screen.getByText('Asha Mollel')).toBeInTheDocument();
    expect(screen.queryByText('Peter Mushi')).not.toBeInTheDocument();
  });

  it('offers the whole list of a kind when more wait than are shown', async () => {
    vi.mocked(getActions).mockResolvedValue(view());
    renderBell();
    await openBell('13 jobs waiting for you');
    await userEvent.click(await screen.findByRole('button', { name: /Proposals returned to you/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Show all 7' }));
    expect(await screen.findByText('Every proposal needing action')).toBeInTheDocument();
  });

  it('opens the only kind there is straight away', async () => {
    vi.mocked(getActions).mockResolvedValue(view({ total: 7, groups: [view().groups[0]!] }));
    renderBell();
    await openBell('7 jobs waiting for you');
    expect(await screen.findByText('Peter Mushi')).toBeInTheDocument();
  });

  it('says plainly when nothing waits', async () => {
    vi.mocked(getActions).mockResolvedValue(view({ total: 0, groups: [] }));
    renderBell();
    await openBell('No jobs waiting for you');
    expect(await screen.findByText('Nothing is waiting for you.')).toBeInTheDocument();
  });

  it('never shows a number it could not count', async () => {
    vi.mocked(getActions).mockRejectedValue(new Error('down'));
    renderBell();
    await openBell('Jobs waiting for you');
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not check for jobs just now');
  });
});

describe('actionLinks', () => {
  const item = view().groups[0]!.items[0]!;

  it('sends each job to the page that does it, in the viewer’s own console', () => {
    expect(actionLink('agents', 'proposal-returned', item)).toBe('/agents/proposals/case-1');
    expect(actionLink('staff', 'claim-settle', { ...item, reference: 'c-9' })).toBe('/staff/claims/c-9');
    expect(actionLink('staff', 'signatures', item)).toBe('/staff/approvals');
    expect(actionLink('staff', 'something-new', item)).toBeNull();
  });

  it('names each kind and knows where its full list is', () => {
    expect(kindLabel('claim-assess')).toBe('Claims to assess');
    expect(kindLabel('something-new')).toBe('something-new');
    expect(allLink('staff', 'proposal-review')).toBe('/staff/underwriting?status=OPEN');
    expect(allLink('staff', 'credit-life-file')).toBeNull();
  });

  it('says how long a job has waited in words', () => {
    const now = Date.parse('2026-11-03T12:00:00Z');
    expect(waitedFor('2026-11-03T11:59:40Z', now)).toBe('just now');
    expect(waitedFor('2026-11-03T11:45:00Z', now)).toBe('15 min ago');
    expect(waitedFor('2026-11-03T09:00:00Z', now)).toBe('3 h ago');
    expect(waitedFor('2026-11-02T12:00:00Z', now)).toBe('yesterday');
    expect(waitedFor('2026-10-30T12:00:00Z', now)).toBe('4 days ago');
  });
});
