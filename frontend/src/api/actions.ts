import { get } from '@/lib/http';
import type { components } from '@/types/api/approvals';

/**
 * The jobs waiting for the viewer, by kind of work -- the bell (2026-11-03). Read live from each module's records, so a
 * job leaves the list when anybody does it. `incomplete` means some module could not be read: the number may be short,
 * never "all".
 */
export type ActionsView = components['schemas']['ActionsView'];
export type ActionGroup = components['schemas']['ActionGroup'];
export type ActionItem = components['schemas']['ActionItem'];

export function getActions(): Promise<ActionsView> {
  return get<ActionsView>('/actions');
}
