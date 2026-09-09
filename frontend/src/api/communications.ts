import { get, put } from '@/lib/http';
import type { NotificationDispatchView, NotificationTemplateView } from './types';

/**
 * The communication module's read surface, and the one write.
 *
 * There is deliberately no send function here, because there is no send endpoint. Messages are
 * produced by consuming domain events and by a scheduled sweep — nothing on this platform
 * contacts a customer because a screen asked it to.
 */

export interface DispatchSearchParams {
  partyId?: string;
  /** The filter the desk reaches for: "has this customer been told about THIS policy". */
  policyNumber?: string;
  status?: string;
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

/** `GET /notifications/dispatches` — the outbox, newest first. */
export function listDispatches(params: DispatchSearchParams = {}): Promise<NotificationDispatchView[]> {
  return get<NotificationDispatchView[]>('/notifications/dispatches', {
    params: {
      ...(params.partyId ? { partyId: params.partyId } : {}),
      ...(params.policyNumber ? { policyNumber: params.policyNumber } : {}),
      ...(params.status ? { status: params.status } : {}),
    },
  });
}
