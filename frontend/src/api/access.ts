import { get } from '@/lib/http';
import type { components as IamComponents } from '@/types/api/iam';

/** What the signed-in staff member may see and do (2026-10-26): roles, the one the badge shows, and permission codes. */
export type MyAccess = IamComponents['schemas']['MyAccess'];

export function getMyAccess(): Promise<MyAccess> {
  return get<MyAccess>('/me/access');
}
