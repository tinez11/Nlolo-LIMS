import { create } from 'zustand';
import {
  createRole, createStaffUser, decideChange, getStaffUser, listPendingChanges, listPermissions, listRoles,
  listStaffUsers, resetStaffPassword, setStaffAccess, setStaffActive, updateRole,
  type AccessChangeView, type AccessOutcome, type CreatedStaffUser, type NewStaffUser, type PasswordIssued,
  type PermissionInfo, type RoleDraft, type RoleView, type StaffUser, type StaffUserDetail,
} from '@/api/access';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * Users, roles and permissions (2026-10-26) -- the Administration screens.
 *
 * `issued` holds a one-time password for the user page to show ONCE after it was made, the way the portal panel
 * holds its invite outcome: it lives in memory only, and is cleared the moment the page has shown it and moved on.
 */
interface IamState {
  users: Resource<StaffUser[]>;
  user: Record<string, Resource<StaffUserDetail>>;
  roles: Resource<RoleView[]>;
  catalogue: Resource<PermissionInfo[]>;
  pending: Resource<AccessChangeView[]>;
  creatingUser: Resource<CreatedStaffUser>;
  creatingRole: Resource<RoleView>;
  /** Keyed by userId, roleId or changeId: one in-flight act per record. */
  acting: Record<string, Resource<AccessOutcome | true>>;
  issued: Record<string, PasswordIssued>;

  loadUsers: (q?: string) => Promise<void>;
  loadUser: (userId: string) => Promise<void>;
  loadRoles: () => Promise<void>;
  loadCatalogue: () => Promise<void>;
  loadPending: () => Promise<void>;
  createUser: (user: NewStaffUser) => Promise<void>;
  resetCreateUser: () => void;
  createRole: (draft: RoleDraft) => Promise<void>;
  resetCreateRole: () => void;
  saveAccess: (userId: string, roleCodes: string[], permissions: string[]) => Promise<void>;
  setActive: (userId: string, active: boolean) => Promise<void>;
  resetPassword: (userId: string) => Promise<void>;
  saveRole: (roleId: string, draft: RoleDraft) => Promise<void>;
  decide: (changeId: string, approve: boolean) => Promise<void>;
  resetActing: (key: string) => void;
  clearIssued: (userId: string) => void;
}

export const useIamStore = create<IamState>((set, getState) => {
  const act = <T extends AccessOutcome | true>(key: string, run: () => Promise<T>) =>
    track(
      `iam.act.${key}`,
      getState().acting[key] ?? idle<AccessOutcome | true>(),
      (next) => set((s) => ({ acting: { ...s.acting, [key]: next } })),
      run,
    );

  return {
    users: idle(),
    user: {},
    roles: idle(),
    catalogue: idle(),
    pending: idle(),
    creatingUser: idle(),
    creatingRole: idle(),
    acting: {},
    issued: {},

    loadUsers: (q) => track('iam.users', getState().users, (next) => set({ users: next }), () => listStaffUsers(q)),

    loadUser: (userId) =>
      track(
        `iam.user.${userId}`,
        getState().user[userId] ?? idle<StaffUserDetail>(),
        (next) => set((s) => ({ user: { ...s.user, [userId]: next } })),
        () => getStaffUser(userId),
      ),

    loadRoles: () => track('iam.roles', getState().roles, (next) => set({ roles: next }), () => listRoles()),

    loadCatalogue: () => {
      if (getState().catalogue.status === 'success') return Promise.resolve();
      return track('iam.catalogue', getState().catalogue, (next) => set({ catalogue: next }), () => listPermissions());
    },

    loadPending: () =>
      track('iam.pending', getState().pending, (next) => set({ pending: next }), () => listPendingChanges()),

    createUser: (user) =>
      track('iam.createUser', getState().creatingUser, (next) => set({ creatingUser: next }), async () => {
        const created = await createStaffUser(user);
        set((s) => ({ issued: { ...s.issued, [created.user.userId]: { delivery: created.delivery,
          temporaryPassword: created.temporaryPassword } } }));
        return created;
      }),

    resetCreateUser: () => set({ creatingUser: idle() }),

    createRole: (draft) =>
      track('iam.createRole', getState().creatingRole, (next) => set({ creatingRole: next }), () => createRole(draft)),

    resetCreateRole: () => set({ creatingRole: idle() }),

    saveAccess: (userId, roleCodes, permissions) =>
      act(userId, async () => {
        const outcome = await setStaffAccess(userId, roleCodes, permissions);
        void getState().loadUser(userId);
        return outcome;
      }),

    setActive: (userId, active) =>
      act(userId, async () => {
        await setStaffActive(userId, active);
        void getState().loadUser(userId);
        return true as const;
      }),

    resetPassword: (userId) =>
      act(userId, async () => {
        const issued = await resetStaffPassword(userId);
        set((s) => ({ issued: { ...s.issued, [userId]: issued } }));
        void getState().loadUser(userId);
        return true as const;
      }),

    saveRole: (roleId, draft) =>
      act(roleId, async () => {
        const outcome = await updateRole(roleId, draft);
        void getState().loadRoles();
        return outcome;
      }),

    decide: (changeId, approve) =>
      act(changeId, async () => {
        await decideChange(changeId, approve);
        void getState().loadPending();
        void getState().loadUsers();
        return true as const;
      }),

    resetActing: (key) =>
      set((s) => {
        if (!(key in s.acting)) return s;
        const { [key]: _discard, ...rest } = s.acting;
        return { acting: rest };
      }),

    clearIssued: (userId) =>
      set((s) => {
        if (!(userId in s.issued)) return s;
        const { [userId]: _discard, ...rest } = s.issued;
        return { issued: rest };
      }),
  };
});

export const selectUser = (userId: string) => (s: IamState) => s.user[userId] ?? idle<StaffUserDetail>();
export const selectActing = (key: string) => (s: IamState) => s.acting[key] ?? idle<AccessOutcome | true>();
