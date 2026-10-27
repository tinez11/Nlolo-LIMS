import { get, post, put } from '@/lib/http';
import type { components as IamComponents } from '@/types/api/iam';

type S = IamComponents['schemas'];

/** What the signed-in staff member may see and do (2026-10-26): roles, the one the badge shows, and permission codes. */
export type MyAccess = S['MyAccess'];
export type PermissionInfo = S['PermissionInfo'];
export type RoleView = S['RoleView'];
export type RoleDraft = S['RoleDraft'];
export type StaffUser = S['UserView'];
export type StaffUserDetail = S['UserDetail'];
export type AccessChangeView = S['ChangeView'];
export type NewStaffUser = S['NewUser'];
export type CreatedStaffUser = S['CreatedUser'];
export type AccessOutcome = S['Outcome'];
export type PasswordIssued = S['PasswordIssued'];
export type StaffNameView = S['StaffName'];

/** Staff members' names by token subject (2026-10-27); a subject the platform does not know is left out. */
export function getStaffNames(ids: string[]): Promise<StaffNameView[]> {
  return get<StaffNameView[]>('/staff-names', { params: { ids: ids.join(',') } });
}

export function getMyAccess(): Promise<MyAccess> {
  return get<MyAccess>('/me/access');
}

export function listPermissions(): Promise<PermissionInfo[]> {
  return get<PermissionInfo[]>('/access/permissions');
}

export function listRoles(): Promise<RoleView[]> {
  return get<RoleView[]>('/access/roles');
}

export function createRole(draft: RoleDraft): Promise<RoleView> {
  return post<RoleView>('/access/roles', draft);
}

export function updateRole(roleId: string, draft: RoleDraft): Promise<AccessOutcome> {
  return put<AccessOutcome>(`/access/roles/${encodeURIComponent(roleId)}`, draft);
}

export function listStaffUsers(q?: string): Promise<StaffUser[]> {
  return get<StaffUser[]>('/access/users', { params: q ? { q } : {} });
}

export function getStaffUser(userId: string): Promise<StaffUserDetail> {
  return get<StaffUserDetail>(`/access/users/${encodeURIComponent(userId)}`);
}

export function createStaffUser(user: NewStaffUser): Promise<CreatedStaffUser> {
  return post<CreatedStaffUser>('/access/users', user);
}

export function setStaffAccess(userId: string, roleCodes: string[], permissions: string[]): Promise<AccessOutcome> {
  return put<AccessOutcome>(`/access/users/${encodeURIComponent(userId)}/access`, { roleCodes, permissions });
}

export function setStaffActive(userId: string, active: boolean): Promise<void> {
  return put<void>(`/access/users/${encodeURIComponent(userId)}/active`, { active });
}

export function resetStaffPassword(userId: string): Promise<PasswordIssued> {
  return post<PasswordIssued>(`/access/users/${encodeURIComponent(userId)}/password-reset`, {});
}

export function listPendingChanges(): Promise<AccessChangeView[]> {
  return get<AccessChangeView[]>('/access/changes/pending');
}

export function decideChange(changeId: string, approve: boolean): Promise<void> {
  return post<void>(`/access/changes/${encodeURIComponent(changeId)}/${approve ? 'approval' : 'rejection'}`, {});
}
