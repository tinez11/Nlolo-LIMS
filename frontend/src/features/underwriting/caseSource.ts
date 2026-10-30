import type { CaseSource } from '@/api/underwriting';

/**
 * Where a case came from (2026-10-29, the user: the underwriter must know the source -- simple, no complications). The
 * server reads it from what the case records; a call back is a customer who asked an adviser and gave no amount yet.
 */
export const CASE_SOURCES: readonly CaseSource[] = ['CUSTOMER_ONLINE', 'CALL_BACK', 'AGENT', 'STAFF'];

export const CASE_SOURCE_LABEL: Record<CaseSource, string> = {
  CUSTOMER_ONLINE: 'Customer online',
  CALL_BACK: 'Call back',
  AGENT: 'Agent',
  STAFF: 'Staff',
};

export const CASE_SOURCE_HINT: Record<CaseSource, string> = {
  CUSTOMER_ONLINE: 'The customer applied in the portal, priced and with their health answers.',
  CALL_BACK: 'The customer asked an adviser in the portal and gave no amount. Call them to agree the cover, then complete the proposal.',
  AGENT: 'Submitted by an agent.',
  STAFF: 'Opened in the office.',
};

export function isCaseSource(value: string | null): value is CaseSource {
  return value != null && (CASE_SOURCES as readonly string[]).includes(value);
}
