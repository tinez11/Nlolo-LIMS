import { z } from 'zod';

/** Dismissing an unposted event (IFRS 17 I3a): closed without a journal, so the reason is the record. Mirrors PostingQueueApiImpl. */
export const dismissSchema = z.object({
  reason: z
    .string()
    .trim()
    .min(1, 'Say why this event is dismissed rather than posted')
    .max(500, 'At most 500 characters'),
});

export type DismissValues = z.infer<typeof dismissSchema>;

/** A rule's models as the console reads them: ANY is any classified contract, NONE an event with no policy. */
export function modelsLabel(models: readonly string[]): string {
  return models
    .map((m) => (m === 'ANY' ? 'any classified' : m === 'NONE' ? 'no policy' : m))
    .join(', ');
}

/** A rule's `when`, e.g. "purpose = TOP_UP_REFUND"; empty for a rule that tests nothing. */
export function whenLabel(when: Record<string, string>): string {
  return Object.entries(when)
    .map(([k, v]) => `${k} = ${v}`)
    .join(', ');
}
