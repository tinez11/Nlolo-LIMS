import { get, post } from '@/lib/http';
import type {
  CoveredLifeView,
  GroupFuneralFamilyView,
  GroupFuneralJoiningReport,
  GroupFuneralLifeInput,
  GroupScheduleReading,
  GroupScheduleResult,
  PolicyMemberView,
} from './types';

/**
 * Group funeral schemes (2026-10-07): an association's members and their families on one plan of a
 * FUNERAL product sold to groups, billed members x the plan's group rate.
 */

const enc = encodeURIComponent;

/** Every family on the scheme, in the order they joined, the main member first in each. */
export function listGroupFuneralFamilies(policyNumber: string): Promise<GroupFuneralFamilyView[]> {
  return get<GroupFuneralFamilyView[]>(`/group-schemes/${enc(policyNumber)}/families`);
}

/** A family joins (underwriters): the bill rises by one group rate from the next billing date. */
export function addGroupFuneralFamily(
  policyNumber: string,
  lives: GroupFuneralLifeInput[],
  joinedOn: string | null,
): Promise<PolicyMemberView> {
  return post<PolicyMemberView>(`/group-schemes/${enc(policyNumber)}/families`, { joinedOn, lives });
}

/** A life joins a member's family from today; the bill does not change. */
export function addGroupFuneralLife(
  policyNumber: string,
  policyMemberId: string,
  life: GroupFuneralLifeInput,
): Promise<CoveredLifeView> {
  return post<CoveredLifeView>(`/group-schemes/${enc(policyNumber)}/members/${enc(policyMemberId)}/lives`, life);
}

/** A dependant comes off at the end of this month. */
export function removeGroupFuneralLife(policyNumber: string, coveredLifeId: string, reason: string): Promise<CoveredLifeView> {
  return post<CoveredLifeView>(`/group-schemes/${enc(policyNumber)}/covered-lives/${enc(coveredLifeId)}/removal`,
    { reason: reason === '' ? null : reason });
}

/** A member leaves with their family, covered to the end of that month (underwriters). */
export function groupFuneralMemberLeaves(policyNumber: string, policyMemberId: string, leftOn: string): Promise<PolicyMemberView> {
  return post<PolicyMemberView>(`/group-schemes/${enc(policyNumber)}/members/${enc(policyMemberId)}/departure`,
    { leftOn });
}

/** Families join from the association's file; the report names what joined and every refusal. */
export function joinGroupFuneralFamilies(policyNumber: string, file: File): Promise<GroupFuneralJoiningReport> {
  const form = new FormData();
  form.append('file', file);
  return post<GroupFuneralJoiningReport>(`/group-schemes/${enc(policyNumber)}/funeral-joiners`, form);
}

/** The schedule file's header and an example family -- one format for a proposal and for joiners. */
export function downloadGroupScheduleTemplate(): Promise<Blob> {
  return get<Blob>('/underwriting/group-schedule-template', { responseType: 'blob', headers: { Accept: 'text/csv' } });
}

/** Reads the association's file with the server's own parser, storing nothing: the lives and every row's problem. */
export function readGroupSchedule(file: File): Promise<GroupScheduleReading> {
  const form = new FormData();
  form.append('file', file);
  return post<GroupScheduleReading>('/underwriting/group-schedule/reading', form);
}

/** Replaces an undecided proposal's families from the association's file; all or nothing. */
export function replaceGroupSchedule(caseId: string, file: File): Promise<GroupScheduleResult> {
  const form = new FormData();
  form.append('file', file);
  return post<GroupScheduleResult>(`/underwriting/cases/${enc(caseId)}/group-schedule`, form);
}
