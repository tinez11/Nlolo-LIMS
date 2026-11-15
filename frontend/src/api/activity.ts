import { get } from '@/lib/http';
import type { components } from '@/types/api/audit';

/**
 * The activity log (2026-11-14): who did what, when, to what, and how it ended. Holders of `audit.view` only. Lines
 * mask personal numbers; an opened line does not.
 */
export type ActivityLine = components['schemas']['ActivityLine'];
export type ActivityPage = components['schemas']['ActivityPage'];
export type ActivityDetail = components['schemas']['ActivityDetail'];
export type ActivityChange = components['schemas']['ActivityChange'];
export type ActivityEvent = components['schemas']['ActivityEvent'];
export type ActivityPerson = components['schemas']['ActivityPerson'];
export type ActivityKind = components['schemas']['ActivityKind'];
export type ActivityOutcome = components['schemas']['ActivityOutcome'];

export interface ActivityFilters {
  /** The acting person's subject, or `SYSTEM`. */
  person?: string;
  recordType?: string;
  recordId?: string;
  q?: string;
  kind?: ActivityKind;
  outcome?: ActivityOutcome;
  /** ISO instants; the server defaults to the last seven days. */
  from?: string;
  to?: string;
}

const enc = encodeURIComponent;

/** Only the filters that are set: an empty one would read as "match nothing". */
function params(filters: ActivityFilters): Record<string, string> {
  return Object.fromEntries(Object.entries(filters).filter(([, v]) => v !== undefined && v !== '')) as Record<
    string,
    string
  >;
}

export function listActivity(filters: ActivityFilters, page = 0, pageSize = 25): Promise<ActivityPage> {
  return get<ActivityPage>('/activity', { params: { ...params(filters), page, pageSize } });
}

export function getActivity(activityId: string): Promise<ActivityDetail> {
  return get<ActivityDetail>(`/activity/${enc(activityId)}`);
}

export function listActivityPeople(): Promise<ActivityPerson[]> {
  return get<ActivityPerson[]>('/activity/people');
}

export function downloadActivity(filters: ActivityFilters): Promise<Blob> {
  return get<Blob>('/activity.csv', { params: params(filters), responseType: 'blob', headers: { Accept: 'text/csv' } });
}
