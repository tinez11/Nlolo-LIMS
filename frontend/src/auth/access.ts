import { useAccessStore } from '@/store/accessStore';

/**
 * What the signed-in staff member may do, as the console asks it (2026-10-26): `can('underwriting.decide')`.
 *
 * Read from `GET /me/access`, which the staff shell loads BEFORE it renders any page -- so every component can ask
 * synchronously during render, and a button the person cannot use is never drawn, not drawn and then removed.
 * The server checks every act regardless; this only decides what to show.
 *
 * Outside the staff console (agents, customers) nothing is loaded and every answer is false: those realms are
 * scoped by who they are, not by permissions, and never reach a staff-gated control.
 */
export function can(code: string): boolean {
  const permissions = useAccessStore.getState().mine.data?.permissions;
  return permissions ? permissions.includes(code) : false;
}

/** True when the person holds any one of them. */
export function canAny(...codes: string[]): boolean {
  return codes.some(can);
}
