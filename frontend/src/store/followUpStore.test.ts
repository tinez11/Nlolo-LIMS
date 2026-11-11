import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as followUpApi from '@/api/followUp';
import type { CollectionActionView, FollowUpPage } from '@/api/followUp';
import { idle } from './createResourceSlice';
import { useFollowUpStore } from './followUpStore';

vi.mock('@/api/followUp');

const INVOICE = 'c0ffee00-0000-4000-8000-000000000001';
const OTHER = 'c0ffee00-0000-4000-8000-000000000002';

const page = (total: number): FollowUpPage => ({
  rows: [],
  total,
  page: 0,
  pageSize: 50,
  dueToday: 0,
  dueSoon: 0,
  overdue: total,
  overdueOwed: 0,
});

const call: CollectionActionView = {
  kind: 'CALL',
  outcome: 'WILL_PAY',
  promisedOn: null,
  note: null,
  recordedBy: 'Asha',
  recordedAt: '2026-11-11T08:00:00Z',
};

beforeEach(() => {
  vi.clearAllMocks();
  useFollowUpStore.setState({ list: idle(), figures: idle(), acting: {}, query: null, mine: false });
});

describe('followUpStore', () => {
  it('reads the list again after a call is recorded, with the same query', async () => {
    vi.mocked(followUpApi.listFollowUp).mockResolvedValueOnce(page(2)).mockResolvedValueOnce(page(1));
    vi.mocked(followUpApi.recordCall).mockResolvedValue(call);
    const query = { view: 'OVERDUE' as const, soonDays: 7 };
    await useFollowUpStore.getState().load(query, false);

    const ok = await useFollowUpStore.getState().recordCall({ invoiceId: INVOICE, outcome: 'WILL_PAY' });

    expect(ok).toBe(true);
    expect(followUpApi.listFollowUp).toHaveBeenCalledTimes(2);
    expect(followUpApi.listFollowUp).toHaveBeenLastCalledWith(query);
    expect(useFollowUpStore.getState().list.data?.total).toBe(1);
  });

  it('keeps the agent on their own list when they send a reminder', async () => {
    vi.mocked(followUpApi.listMyFollowUp).mockResolvedValue(page(1));
    vi.mocked(followUpApi.sendReminder).mockResolvedValue({ ...call, kind: 'REMINDER', outcome: null });
    await useFollowUpStore.getState().load({ view: 'DUE_TODAY', soonDays: 7 }, true);

    await useFollowUpStore.getState().sendReminder(INVOICE);

    expect(followUpApi.listMyFollowUp).toHaveBeenCalledTimes(2);
    expect(followUpApi.listFollowUp).not.toHaveBeenCalled();
  });

  it('keeps a refusal on its own row and does not reload', async () => {
    vi.mocked(followUpApi.listFollowUp).mockResolvedValue(page(1));
    vi.mocked(followUpApi.sendReminder).mockRejectedValue({ status: 409, message: 'A reminder was already sent today' });
    await useFollowUpStore.getState().load({ view: 'OVERDUE', soonDays: 7 }, false);

    const ok = await useFollowUpStore.getState().sendReminder(INVOICE);

    expect(ok).toBe(false);
    expect(useFollowUpStore.getState().acting[INVOICE]?.status).toBe('error');
    expect(useFollowUpStore.getState().acting[OTHER]).toBeUndefined();
    expect(followUpApi.listFollowUp).toHaveBeenCalledTimes(1);
  });
});
