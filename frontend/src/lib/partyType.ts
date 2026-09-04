import type { PartyType } from '@/api/types';

/**
 * Backend party-type literals as English, in one place.
 *
 * Explicit map rather than a generic humaniser, the same reason `PartyDetailPage`'s
 * identity-document and smoker-status maps are explicit: a fourth `PartyType` added
 * server-side should fail the typecheck here, not silently render as `SOLE_TRADER`.
 *
 * Shared rather than per-screen because the whole point of splitting the register was to
 * make "am I looking at a person or a company" unmissable, and it would not be if the
 * register said "Company" and the record it opened said "CORPORATE".
 *
 * `UNKNOWN` is the ABSENT case, not a fourth type: every field on `PartyView` is optional
 * on the wire, so a row with no type is possible and must render as an em dash rather
 * than as blank space that looks like a rendering bug.
 */
export const PARTY_TYPE_LABELS: Record<PartyType | 'UNKNOWN', string> = {
  INDIVIDUAL: 'Individual',
  CORPORATE: 'Company',
  GROUP: 'Group',
  UNKNOWN: '—',
};

export function partyTypeLabel(partyType: PartyType | null | undefined): string {
  return PARTY_TYPE_LABELS[partyType ?? 'UNKNOWN'];
}
