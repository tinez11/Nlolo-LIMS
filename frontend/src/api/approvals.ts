import { get } from '@/lib/http';
import type { components } from '@/types/api/approvals';

/**
 * What waits for the viewer's signature (2026-10-27). One read, composed server-side: every module that holds an
 * approval reports its own rows, and the answer keeps only the kinds the viewer may act on. A group that could not be
 * read is `unreadable` -- never a 0.
 */
export type ApprovalsView = components['schemas']['ApprovalsView'];
export type ApprovalGroup = components['schemas']['ApprovalGroup'];
export type ApprovalRow = components['schemas']['ApprovalRow'];

export function getApprovals(): Promise<ApprovalsView> {
  return get<ApprovalsView>('/approvals');
}
