import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PartyType } from './types';

const get = vi.fn();
vi.mock('@/lib/http', () => ({
  get: (...args: unknown[]) => get(...args),
  post: vi.fn(),
}));

const { PARTY_AREAS, searchParties } = await import('./party');

beforeEach(() => {
  get.mockReset();
  get.mockResolvedValue({ items: [], page: { page: 0, pageSize: 20, totalElements: 0 } });
});

/**
 * The register is two areas, and these are the two ways that split can silently become a
 * lie: an area that stops covering a type it claims to cover, and a type filter that
 * never reaches the server.
 */
describe('PARTY_AREAS', () => {
  it('puts corporates AND groups in the organisations area', () => {
    // The falsifiable half. "Corporate & groups" naming a set that omits GROUP is the
    // failure this guards: no GROUP party can be created over HTTP today, so the area
    // would look correct for as long as that stayed true and then quietly start hiding
    // rows the day it changed.
    expect(PARTY_AREAS.organisations).toContain('CORPORATE');
    expect(PARTY_AREAS.organisations).toContain('GROUP');
  });

  it('keeps individuals to individuals alone', () => {
    expect(PARTY_AREAS.individuals).toEqual(['INDIVIDUAL']);
    expect(PARTY_AREAS.individuals).not.toContain('CORPORATE');
  });

  it('covers every party type across the two areas, so no type is unreachable', () => {
    const everyType: PartyType[] = ['INDIVIDUAL', 'CORPORATE', 'GROUP'];
    const covered = [...PARTY_AREAS.individuals, ...PARTY_AREAS.organisations];
    for (const type of everyType) expect(covered).toContain(type);
  });
});

describe('searchParties partyType serialisation', () => {
  it('sends the area as one comma-separated parameter', async () => {
    // The endpoint declares `partyType` as a comma-separated string, not a repeated
    // parameter: its contract validator rejects the repeated form outright. Sending an
    // array here would serialise as `partyType[]=` or `partyType=A&partyType=B` and be
    // refused, so the joining is load-bearing.
    await searchParties({ partyTypes: PARTY_AREAS.organisations });

    expect(get).toHaveBeenCalledTimes(1);
    expect(get.mock.calls[0]?.[1]?.params).toMatchObject({ partyType: 'CORPORATE,GROUP' });
  });

  it('omits the parameter entirely when no types are given', async () => {
    // Absent means "every type". An empty `partyType=` would fail the endpoint's own
    // pattern, and this is the request every existing caller makes.
    await searchParties({});

    expect(get.mock.calls[0]?.[1]?.params).not.toHaveProperty('partyType');
  });

  it('omits the parameter for an empty array rather than sending nothing-matches', async () => {
    await searchParties({ partyTypes: [] });

    expect(get.mock.calls[0]?.[1]?.params).not.toHaveProperty('partyType');
  });

  it('still carries the other filters alongside the type', async () => {
    await searchParties({
      partyTypes: PARTY_AREAS.individuals,
      kycStatus: 'PENDING',
      q: 'amina',
    });

    expect(get.mock.calls[0]?.[1]?.params).toMatchObject({
      partyType: 'INDIVIDUAL',
      kycStatus: 'PENDING',
      q: 'amina',
    });
  });
});
