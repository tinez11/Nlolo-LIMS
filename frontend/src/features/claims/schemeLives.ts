import type { CoveredLifeView, GroupFuneralFamilyView } from '@/api/types';
import { formatDate } from '@/lib/dates';

/**
 * Choosing who died on a funeral policy (2026-10-08). A group funeral scheme holds every family of an
 * association -- thirty members are two hundred lives -- so the claim picks the family first (by the
 * association's member number or the main member's name) and then one of that family's lives. Pure, so
 * the choices are tested without the page.
 */

/** "M001 · martin lema": how a family reads in the claim's family list. */
export function familyLabel(family: GroupFuneralFamilyView): string {
  return [family.memberReference, family.mainMemberName].filter(Boolean).join(' · ');
}

/** The families a search matches: member number or main member's name, any part, any case. Blank matches all. */
export function matchFamilies(families: GroupFuneralFamilyView[], query: string): GroupFuneralFamilyView[] {
  const q = query.trim().toLowerCase();
  if (q === '') return families;
  return families.filter((f) => (f.memberReference ?? '').toLowerCase().includes(q)
    || (f.mainMemberName ?? '').toLowerCase().includes(q));
}

/**
 * The family whose main member is the claimant -- a dependant's death is paid to the main member, so
 * the claimant usually says which family it is. Null when the claimant heads no family here.
 */
export function familyOfClaimant(families: GroupFuneralFamilyView[], claimantPartyId: string): GroupFuneralFamilyView | null {
  if (!claimantPartyId) return null;
  return families.find((f) => f.lives.some((l) => l.role === 'MAIN_MEMBER' && l.partyId === claimantPartyId)) ?? null;
}

/**
 * The lives a death claim may name: every life still covered, and one whose cover has ended -- a death
 * can be reported after a life comes off the policy, and the date of death decides whether it was
 * covered. A life that ended by dying is left out: its death is already claimed.
 */
export function claimableLives(lives: CoveredLifeView[]): CoveredLifeView[] {
  const covered = lives.filter((l) => l.status === 'ACTIVE');
  const ended = lives.filter((l) => l.status !== 'ACTIVE' && l.endReason !== 'DECEASED');
  return [...covered, ...ended];
}

/** "Neema (child)", and for a life no longer covered "Neema (child) — cover ended 1 Mar 2026". */
export function lifeLabel(life: CoveredLifeView): string {
  const base = `${life.fullName} (${life.role.replace('_', ' ').toLowerCase()})`;
  return life.status === 'ACTIVE' ? base : `${base} — cover ended ${formatDate(life.endedOn)}`;
}
