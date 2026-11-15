import { create } from 'zustand';
import {
  getActivity,
  listActivity,
  listActivityPeople,
  type ActivityDetail,
  type ActivityFilters,
  type ActivityPage,
  type ActivityPerson,
} from '@/api/activity';
import { idle, track, type Resource } from './createResourceSlice';

const HISTORY_FROM = '2026-01-01T00:00:00+03:00';

/** The activity log's store (2026-11-14): the filtered page, the people who acted, and opened lines by id. */
interface ActivityState {
  list: Resource<ActivityPage>;
  people: Resource<ActivityPerson[]>;
  details: Record<string, Resource<ActivityDetail>>;
  /** One record's Activity, keyed `type:id`, so two records each keep their own. */
  histories: Record<string, Resource<ActivityPage>>;
  loadList: (filters: ActivityFilters, page: number) => Promise<void>;
  loadHistory: (recordType: string, recordId: string, page: number) => Promise<void>;
  loadPeople: () => Promise<void>;
  loadDetail: (activityId: string) => Promise<void>;
}

export const useActivityStore = create<ActivityState>((set, getState) => ({
  list: idle(),
  people: idle(),
  details: {},
  histories: {},

  loadHistory: (recordType, recordId, page) => {
    const key = `${recordType}:${recordId}`;
    return track(
      `activity.history.${key}`,
      getState().histories[key] ?? idle(),
      (next) => set((s) => ({ histories: { ...s.histories, [key]: next } })),
      // Everything since the platform began keeping the log: a record's Activity is never cut to a week.
      () => listActivity({ recordType, recordId, from: HISTORY_FROM, to: new Date().toISOString() }, page),
    );
  },

  loadList: (filters, page) =>
    track('activity.list', getState().list, (next) => set({ list: next }), () => listActivity(filters, page)),

  loadPeople: () =>
    track('activity.people', getState().people, (next) => set({ people: next }), () => listActivityPeople()),

  loadDetail: (activityId) =>
    track(
      `activity.detail.${activityId}`,
      getState().details[activityId] ?? idle(),
      (next) => set((s) => ({ details: { ...s.details, [activityId]: next } })),
      () => getActivity(activityId),
    ),
}));
