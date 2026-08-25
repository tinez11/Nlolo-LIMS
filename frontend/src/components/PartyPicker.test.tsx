import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as partyApi from '@/api/party';
import type { PartyView } from '@/api/types';
import { PartyPicker } from './PartyPicker';

vi.mock('@/api/party');

const aminaOwner: PartyView = {
  partyId: 'd9937444-3873-4336-9cb7-addb486f3e1b',
  partyType: 'INDIVIDUAL',
  kycStatus: 'VERIFIED',
  displayName: 'Amina Owner',
};

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe('PartyPicker', () => {
  it('shows a placeholder and does not search below the minimum query length', async () => {
    const searchSpy = vi.spyOn(partyApi, 'searchParties');
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(screen.getByPlaceholderText('Type a name to search'), 'A');

    expect(screen.getByText('Type a name to search')).toBeInTheDocument();
    expect(searchSpy).not.toHaveBeenCalled();
  });

  it('searches after a debounce once the query reaches 2 characters, and lists results', async () => {
    vi.spyOn(partyApi, 'searchParties').mockResolvedValue({
      items: [aminaOwner],
      page: { page: 0, pageSize: 10, totalElements: 1 },
    });
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(screen.getByPlaceholderText('Type a name to search'), 'Amina');

    await waitFor(() => expect(screen.getByText('Amina Owner')).toBeInTheDocument(), { timeout: 2000 });
    expect(partyApi.searchParties).toHaveBeenCalledWith(
      expect.objectContaining({ q: 'Amina', pageSize: 10 }),
    );
  });

  it('searches immediately, bypassing the debounce and minimum length, when the query is a full UUID', async () => {
    vi.spyOn(partyApi, 'searchParties').mockResolvedValue({
      items: [aminaOwner],
      page: { page: 0, pageSize: 10, totalElements: 1 },
    });
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(
      screen.getByPlaceholderText('Type a name to search'),
      'd9937444-3873-4336-9cb7-addb486f3e1b',
    );

    await waitFor(() => expect(screen.getByText('Amina Owner')).toBeInTheDocument(), { timeout: 1000 });
  });

  it('shows a "no matches" message for a real query with zero results', async () => {
    vi.spyOn(partyApi, 'searchParties').mockResolvedValue({
      items: [],
      page: { page: 0, pageSize: 10, totalElements: 0 },
    });
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(screen.getByPlaceholderText('Type a name to search'), 'Nobody Real');

    await waitFor(() => expect(screen.getByText(/No matches for/)).toBeInTheDocument(), { timeout: 2000 });
  });

  it('shows an inline error when the search request fails', async () => {
    vi.spyOn(partyApi, 'searchParties').mockRejectedValue(new Error('network down'));
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(screen.getByPlaceholderText('Type a name to search'), 'Amina');

    await waitFor(() => expect(screen.getByText(/Couldn't search/)).toBeInTheDocument(), { timeout: 2000 });
  });

  it('calls onChange with both the partyId and the full PartyView on selection, and closes', async () => {
    vi.spyOn(partyApi, 'searchParties').mockResolvedValue({
      items: [aminaOwner],
      page: { page: 0, pageSize: 10, totalElements: 1 },
    });
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={onChange} />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(screen.getByPlaceholderText('Type a name to search'), 'Amina');
    await waitFor(() => screen.getByText('Amina Owner'));
    await user.click(screen.getByText('Amina Owner'));

    expect(onChange).toHaveBeenCalledWith(aminaOwner.partyId, aminaOwner);
    expect(screen.queryByPlaceholderText('Type a name to search')).not.toBeInTheDocument();
  });

  it('passes kycStatus through to the search when a pre-filter is set', async () => {
    const searchSpy = vi.spyOn(partyApi, 'searchParties').mockResolvedValue({
      items: [],
      page: { page: 0, pageSize: 10, totalElements: 0 },
    });
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={vi.fn()} kycStatus="VERIFIED" />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(screen.getByPlaceholderText('Type a name to search'), 'Amina');

    await waitFor(() =>
      expect(searchSpy).toHaveBeenCalledWith(expect.objectContaining({ kycStatus: 'VERIFIED' })),
    );
  });
});
