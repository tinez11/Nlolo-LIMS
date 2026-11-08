import { get, post, put } from '@/lib/http';
import type {
  ChannelStatus,
  DispatchSummary,
  NotificationDispatchView,
  NotificationTemplateView,
  TestSendResult,
} from './types';

/**
 * The communication module's read surface, and the one write.
 *
 * Messages are produced by consuming domain events and by a scheduled sweep. Two sends exist since 2026-11-08, both
 * deliberate and narrow: a test of one template to the editor's own phone or mailbox, and a second try of a message
 * that failed or was not sent.
 */

export interface DispatchSearchParams {
  partyId?: string;
  /** The filter the desk reaches for: "has this customer been told about THIS policy". */
  policyNumber?: string;
  status?: string;
  /** Newest rows only (at most 500). The whole outbox is thousands of rows; the register always sends one. */
  limit?: number;
}

/** `GET /notifications/templates` — staff, tenant-scoped. */
export function listTemplates(): Promise<NotificationTemplateView[]> {
  return get<NotificationTemplateView[]>('/notifications/templates');
}

/**
 * `PUT /notifications/templates/{templateId}` — ADMIN only.
 *
 * Body text only: key, channel and language are identity rather than content. A body introducing
 * a placeholder nothing supplies is refused with 422 rather than shipping a literal hole to
 * every future customer.
 */
export function rewordTemplate(templateId: string, bodyTemplate: string): Promise<NotificationTemplateView> {
  return put<NotificationTemplateView>(`/notifications/templates/${encodeURIComponent(templateId)}`, {
    bodyTemplate,
  });
}

// ---- 2026-11-08: the desk's view at any volume, the channels' honest status, a test, a second try ----

export interface DispatchFilter {
  status?: string;
  channel?: string;
  templateKey?: string;
  policyNumber?: string;
  /** How far back: a week unless asked. */
  sinceDays?: number;
  limit?: number;
}

/** `GET /notifications/dispatches/search` — filtered in the database, newest first, never older than sinceDays. */
export function searchDispatches(f: DispatchFilter): Promise<NotificationDispatchView[]> {
  const params: Record<string, string> = {};
  if (f.status) params.status = f.status;
  if (f.channel) params.channel = f.channel;
  if (f.templateKey) params.templateKey = f.templateKey;
  if (f.policyNumber) params.policyNumber = f.policyNumber;
  if (f.sinceDays) params.sinceDays = String(f.sinceDays);
  if (f.limit) params.limit = String(f.limit);
  return get<NotificationDispatchView[]>('/notifications/dispatches/search', { params });
}

/** `GET /notifications/dispatches/summary` — counts by channel and status, and why the ones not sent were not. */
export function getDispatchSummary(sinceDays: number): Promise<DispatchSummary> {
  return get<DispatchSummary>('/notifications/dispatches/summary', { params: { sinceDays: String(sinceDays) } });
}

/** `GET /notifications/channels` — whether SMS and email reach customers. */
export function getChannels(): Promise<ChannelStatus[]> {
  return get<ChannelStatus[]>('/notifications/channels');
}

/** `POST /notifications/templates/{id}/test` — to the editor's own phone or mailbox; nothing is logged. */
export function sendTestMessage(templateId: string, to: string, values: Record<string, string>): Promise<TestSendResult> {
  return post<TestSendResult>(`/notifications/templates/${encodeURIComponent(templateId)}/test`, { to, values });
}

/** `POST /notifications/dispatches/{id}/resend` — as worded then, to the contact on file today. */
export function resendDispatch(dispatchId: string): Promise<NotificationDispatchView> {
  return post<NotificationDispatchView>(`/notifications/dispatches/${encodeURIComponent(dispatchId)}/resend`, {});
}

/** `GET /notifications/dispatches` — the outbox, newest first. */
export function listDispatches(params: DispatchSearchParams = {}): Promise<NotificationDispatchView[]> {
  return get<NotificationDispatchView[]>('/notifications/dispatches', {
    params: {
      ...(params.partyId ? { partyId: params.partyId } : {}),
      ...(params.policyNumber ? { policyNumber: params.policyNumber } : {}),
      ...(params.status ? { status: params.status } : {}),
      ...(params.limit ? { limit: String(params.limit) } : {}),
    },
  });
}
