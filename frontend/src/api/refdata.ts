import { get } from '@/lib/http';
import type { components as RefdataComponents } from '@/types/api/refdata';

export type ReferenceCodeView = RefdataComponents['schemas']['ReferenceCodeView'];
type ReferenceCodeSetView = RefdataComponents['schemas']['ReferenceCodeSetView'];

/**
 * `GET /reference-codes/{codeSetKey}` -- one global reference list (branches, sales channels ...). IFRS 17 I2's
 * branch and channel pickers read their options here, so a new branch is a refdata row and no console release.
 */
export async function getReferenceCodes(codeSetKey: string): Promise<ReferenceCodeView[]> {
  const body = await get<ReferenceCodeSetView>(`/reference-codes/${encodeURIComponent(codeSetKey)}`);
  return body.values ?? [];
}
