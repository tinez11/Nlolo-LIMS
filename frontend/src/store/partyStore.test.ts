import { describe, expect, it } from 'vitest';
import type { Page, PartyView } from '@/api/types';
import { idle, success } from './createResourceSlice';
import { selectPartyList, usePartyStore } from './partyStore';

function pageOf(items: PartyView[]): Page<PartyView> {
  return { items, page: { page: 0, pageSize: 20, totalElements: items.length } };
}

const anIndividual: PartyView = {
  partyId: 'p-individual',
  partyType: 'INDIVIDUAL',
  kycStatus: 'PENDING',
  displayName: 'A Person',
};

/**
 * REGRESSION, and an e2e test caught it rather than a unit test.
 *
 * `list` was a single slot with a constant track key, on the reasoning that it was "the
 * same on-screen table either way, so only the most recently requested filter should
 * win a race". True while Clients was one register. Once it became two areas, switching
 * from Individuals to Corporate & groups rendered the PREVIOUS area's rows under the new
 * area's heading and caption for as long as the next request was in flight -- the full
 * e2e suite caught twenty cells reading "Individual" inside a table captioned "Corporate
 * and group clients", which is exactly the confusion splitting the register exists to
 * end. It passed when the spec ran alone, because in isolation there was no prior area
 * loaded to bleed through.
 */
describe('party list is keyed by register area', () => {
  it('does not serve one area a page loaded for another', () => {
    usePartyStore.setState({
      list: { individuals: success(pageOf([anIndividual])) },
    });

    const state = usePartyStore.getState();
    expect(selectPartyList('individuals')(state).data?.items).toHaveLength(1);
    // The assertion that matters: the other area is untouched, not "whatever was
    // loaded last".
    expect(selectPartyList('organisations')(state).data).toBeNull();
  });

  it('treats the unsplit list as its own area, not as a synonym for either', () => {
    // The agents realm and the retired staff path both load every type. That is a
    // third table, and it must not inherit a staff area's rows.
    usePartyStore.setState({
      list: { all: success(pageOf([anIndividual])) },
    });

    const state = usePartyStore.getState();
    expect(selectPartyList('all')(state).data?.items).toHaveLength(1);
    expect(selectPartyList('individuals')(state).data).toBeNull();
  });

  it('returns idle for an area nothing has loaded, rather than undefined', () => {
    usePartyStore.setState({ list: {} });

    expect(selectPartyList('organisations')(usePartyStore.getState())).toEqual(idle());
  });
});
