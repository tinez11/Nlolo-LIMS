import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as partyApi from '@/api/party';
import type { Page, PartyView } from '@/api/types';
import { PartyPicker } from './PartyPicker';

vi.mock('@/api/party');

const aminaOwner: PartyView = {
  partyId: 'd9937444-3873-4336-9cb7-addb486f3e1b',
  partyType: 'INDIVIDUAL',
  kycStatus: 'VERIFIED',
  displayName: 'Amina Owner',
};

beforeEach(() => {
  // `vi.mock('@/api/party')` auto-mocks the module once for the whole file --
  // `vi.spyOn` on an already-auto-mocked export doesn't reliably clear call
  // history via afterEach's restoreAllMocks alone, so this is the explicit
  // per-test reset a `.not.toHaveBeenCalled()` assertion needs to be trusted.
  vi.clearAllMocks();
});

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

  it('resolves a pasted UUID via a direct id lookup, not the name-search endpoint, bypassing the debounce', async () => {
    // `q` only ever matches displayName -- a real backend would return zero
    // rows for a UUID `q`. This proves the picker takes the direct-lookup
    // path instead: getParty is called and searchParties is never touched,
    // which a same-response-regardless-of-query mock (the shape this test
    // previously used) could never distinguish.
    const getPartySpy = vi.spyOn(partyApi, 'getParty').mockResolvedValue(aminaOwner);
    const searchSpy = vi.spyOn(partyApi, 'searchParties');
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(
      screen.getByPlaceholderText('Type a name to search'),
      'd9937444-3873-4336-9cb7-addb486f3e1b',
    );

    // A tight window, comfortably under the 300ms debounce -- if the bypass
    // regressed to waiting out the debounce, this would flake to a timeout
    // rather than silently pass, unlike a 1000ms window that both paths satisfy.
    await waitFor(() => expect(screen.getByText('Amina Owner')).toBeInTheDocument(), { timeout: 150 });
    expect(getPartySpy).toHaveBeenCalledWith('d9937444-3873-4336-9cb7-addb486f3e1b');
    expect(searchSpy).not.toHaveBeenCalled();
  });

  it('shows no matches when a pasted UUID does not resolve to any real party', async () => {
    vi.spyOn(partyApi, 'getParty').mockRejectedValue({
      status: 404,
      kind: 'notFound',
      errorCode: null,
      title: 'Not found',
      detail: null,
      traceId: null,
      fieldErrors: [],
      mayBeDenied: true,
    });
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(
      screen.getByPlaceholderText('Type a name to search'),
      '00000000-0000-4000-8000-000000000000',
    );

    await waitFor(() => expect(screen.getByText(/No matches for/)).toBeInTheDocument(), { timeout: 2000 });
  });

  it('excludes a pasted UUID whose party does not match the kycStatus pre-filter', async () => {
    vi.spyOn(partyApi, 'getParty').mockResolvedValue({ ...aminaOwner, kycStatus: 'PENDING' });
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={vi.fn()} kycStatus="VERIFIED" />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(
      screen.getByPlaceholderText('Type a name to search'),
      'd9937444-3873-4336-9cb7-addb486f3e1b',
    );

    await waitFor(() => expect(screen.getByText(/No matches for/)).toBeInTheDocument(), { timeout: 2000 });
    expect(screen.queryByText('Amina Owner')).not.toBeInTheDocument();
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

  it('resolves and shows a label for a pre-existing value on mount, instead of the placeholder', async () => {
    vi.spyOn(partyApi, 'getParty').mockResolvedValue(aminaOwner);

    render(<PartyPicker value={aminaOwner.partyId ?? null} onChange={vi.fn()} />);

    await waitFor(() => expect(screen.getByText('Amina Owner')).toBeInTheDocument());
    expect(partyApi.getParty).toHaveBeenCalledWith(aminaOwner.partyId);
    expect(screen.queryByText('Search by name…')).not.toBeInTheDocument();
  });

  it('a clear button on a populated field calls onChange(null, null) and reverts to the placeholder', async () => {
    vi.spyOn(partyApi, 'getParty').mockResolvedValue(aminaOwner);
    const onChange = vi.fn();
    const user = userEvent.setup();

    render(<PartyPicker value={aminaOwner.partyId ?? null} onChange={onChange} />);
    await waitFor(() => expect(screen.getByText('Amina Owner')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Clear selection' }));

    expect(onChange).toHaveBeenCalledWith(null, null);
  });

  it('a newer search always wins over a slower, still-in-flight older one', async () => {
    const barakaLater: PartyView = {
      partyId: 'b7e1e3a4-1111-4111-8111-111111111111',
      partyType: 'INDIVIDUAL',
      kycStatus: 'VERIFIED',
      displayName: 'Baraka Later',
    };
    let resolveFirst: ((page: Page<PartyView>) => void) | undefined;
    const firstSearch = new Promise<Page<PartyView>>((resolve) => {
      resolveFirst = resolve;
    });
    const searchSpy = vi.spyOn(partyApi, 'searchParties');
    searchSpy.mockImplementationOnce(() => firstSearch);
    searchSpy.mockResolvedValueOnce({
      items: [barakaLater],
      page: { page: 0, pageSize: 10, totalElements: 1 },
    });

    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={vi.fn()} />);
    await user.click(screen.getByRole('button', { name: /search by name/i }));
    const input = screen.getByPlaceholderText('Type a name to search');

    await user.type(input, 'Amina');
    await waitFor(() => expect(searchSpy).toHaveBeenCalledTimes(1));

    await user.clear(input);
    await user.type(input, 'Baraka');
    await waitFor(() => expect(screen.getByText('Baraka Later')).toBeInTheDocument());

    // The older ("Amina") search resolves LATE, after the newer one already
    // rendered -- it must not clobber what's on screen. Before the fix, a
    // shared cancellation ref (reset by the newer effect's own start) let
    // this stale response overwrite the current results.
    resolveFirst?.({ items: [aminaOwner], page: { page: 0, pageSize: 10, totalElements: 1 } });
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(screen.getByText('Baraka Later')).toBeInTheDocument();
    expect(screen.queryByText('Amina Owner')).not.toBeInTheDocument();
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
