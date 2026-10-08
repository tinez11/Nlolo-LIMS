import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as funeralApi from '@/api/funeral';
import * as groupFuneralApi from '@/api/groupFuneral';
import * as policiesApi from '@/api/policies';
import type { CoveredLifeView, GroupFuneralFamilyView, PolicyView } from '@/api/types';
import { GroupFuneralFamiliesPanel } from './GroupFuneralFamiliesPanel';

vi.mock('@/api/funeral');
vi.mock('@/api/groupFuneral');
vi.mock('@/api/policies');
vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { access_token: 'token' } }) }));
let roles: string[] = [];
vi.mock('@/auth/claims', async (original) => ({
  ...(await original<typeof import('@/auth/claims')>()),
  readIdentity: () => ({ roles, subject: 's', partyId: null, tenantId: null, preferredUsername: null, name: null, email: null }),
}));

const life = (over: Partial<CoveredLifeView>): CoveredLifeView => ({
  coveredLifeId: crypto.randomUUID(),
  role: 'CHILD',
  fullName: 'Neema',
  dateOfBirth: '2016-01-01',
  sex: null,
  idNumber: null,
  student: false,
  partyId: null,
  benefit: 50000,
  yearlyPremium: 0,
  pricedAtAge: 10,
  coverStart: '2026-01-01',
  waitingPeriodEnds: null,
  coverEnd: null,
  status: 'ACTIVE',
  endReason: null,
  endedOn: null,
  ...over,
});

const martin = life({ role: 'MAIN_MEMBER', fullName: 'martin lema', dateOfBirth: '1980-01-01', benefit: 1200000 });
const registered = life({ role: 'SPOUSE', fullName: 'Asha', partyId: 'p-2' });

const family: GroupFuneralFamilyView = {
  policyMemberId: 'm-1', memberReference: 'M001', mainMemberName: 'martin lema', status: 'ACTIVE',
  joinedOn: '2026-01-01', leftOn: null, beneficiaryName: null, beneficiaryRelationship: null, beneficiaryPhone: null,
  familyCover: 1250000, lives: [martin, registered, life({})],
};

beforeEach(() => {
  vi.clearAllMocks();
  roles = [];
  vi.mocked(groupFuneralApi.listGroupFuneralFamilies).mockResolvedValue([family]);
  vi.mocked(policiesApi.getPolicy).mockResolvedValue({
    policyNumber: 'GRP-1', status: 'ACTIVE', premiumFrequency: 'ANNUALLY',
    premium: { amount: '42000', currencyCode: 'TZS' }, sumAssured: { amount: '0', currencyCode: 'TZS' },
  } as PolicyView);
});

describe('GroupFuneralFamiliesPanel', () => {
  it('lets claims staff register a main member as a client, even on a yearly plan whose family is fixed', async () => {
    roles = ['CLAIMS_ASSESSOR'];
    vi.mocked(funeralApi.promoteCoveredLife).mockResolvedValue({ ...martin, partyId: 'p-1' });
    render(<GroupFuneralFamiliesPanel policyNumber="GRP-1" onChanged={vi.fn()} />);

    const block = await screen.findByLabelText('Member M001');
    // Already a client: nothing to promote.
    expect(within(block).queryByRole('button', { name: 'Promote Asha to client' })).not.toBeInTheDocument();

    await userEvent.click(within(block).getByRole('button', { name: 'Promote martin lema to client' }));
    await userEvent.selectOptions(screen.getByLabelText('Identity document'), 'NATIONAL_ID');
    await userEvent.type(screen.getByLabelText('Document number'), '19800101-11111-00001-11');
    await userEvent.click(screen.getByRole('button', { name: 'Promote to client' }));

    await waitFor(() => expect(funeralApi.promoteCoveredLife).toHaveBeenCalledWith('GRP-1', martin.coveredLifeId,
      expect.objectContaining({ idType: 'NATIONAL_ID', idNumber: '19800101-11111-00001-11' })));
    expect(groupFuneralApi.listGroupFuneralFamilies).toHaveBeenCalledTimes(2);
  });

  it('does not offer the promotion to staff the server would refuse', async () => {
    roles = ['UNDERWRITER'];
    render(<GroupFuneralFamiliesPanel policyNumber="GRP-1" onChanged={vi.fn()} />);
    await screen.findByLabelText('Member M001');
    expect(screen.queryByRole('button', { name: /Promote .* to client/ })).not.toBeInTheDocument();
  });
});
