import { get } from '@/lib/http';
import type { AuditEntryView, Page } from './types';

/**
 * The audit module's read surface — its first, added in M13.
 *
 * WHAT THIS IS NOT: a who-did-what trail. `audit.audit_log` records
 * `(eventId, eventType, occurredAt, payload)` and is written by the platform's
 * domain-event listener. There is no actor column, and no before/after values
 * and no reason. Five of the six columns a compliance register wants exist
 * nowhere on this platform.
 *
 * So this answers "what happened in this tenant, in order". Any screen over it
 * has to say so rather than presenting an event feed as compliance evidence.
 */

export interface AuditSearchParams {
  /** Prefix on `eventType`, which is `module.EventName` — so `policy.` is a module filter. */
  eventTypePrefix?: string;
  /** Inclusive. Worth setting: the table is partitioned by month and unbounded scans every partition. */
  from?: string;
  /** Inclusive. */
  to?: string;
  page?: number;
  pageSize?: number;
}

const DEFAULT_PAGE_SIZE = 20;
const MAX_PAGE_SIZE = 100;

/** `GET /audit-log` — staff only, tenant-scoped, newest first. */
export async function listAuditEvents(params: AuditSearchParams = {}): Promise<Page<AuditEntryView>> {
  const page = params.page ?? 0;
  const pageSize = Math.min(params.pageSize ?? DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

  const body = await get<{
    items?: AuditEntryView[];
    page?: { page?: number; pageSize?: number; totalElements?: number };
  }>('/audit-log', {
    params: {
      ...(params.eventTypePrefix ? { eventTypePrefix: params.eventTypePrefix } : {}),
      ...(params.from ? { from: params.from } : {}),
      ...(params.to ? { to: params.to } : {}),
      page,
      pageSize,
    },
  });

  return {
    items: body.items ?? [],
    page: {
      page: body.page?.page ?? page,
      pageSize: body.page?.pageSize ?? pageSize,
      totalElements: body.page?.totalElements ?? 0,
    },
  };
}
