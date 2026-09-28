import { isValidElement } from 'react';
import { describe, expect, it } from 'vitest';
import type { ClaimView } from '@/api/types';
import { claimSections } from './claimSections';

/**
 * WHY THIS FILE EXISTS.
 *
 * The claims lane shipped its first six commits with 986 green unit tests and not one of them
 * touched a claims screen: all six existing test files here cover pure functions --
 * `assessorName`, `claimRegisterForm`, `payoutNextStep`, `reopenClaimForm`,
 * `settlementDecisionForm`, `submitClaimAssessmentForm`. So "the unit suite is green" carried
 * no information whatsoever about whether the record still drew, and four e2e failures had to
 * be chased down before that was obvious.
 *
 * `claimSections` is the piece worth pinning. It is the only real logic the redesign added to
 * this lane -- seven role- and status-gated entries, each pairing a jump-link with the panel
 * that link must land on -- and every one of its failure modes is silent on screen.
 */

const CLAIM = {
  claimId: 'c-1',
  claimType: 'DEATH',
  status: 'REGISTERED',
  policyNumber: 'POL-ABC',
  claimantPartyId: 'p-1',
  dateOfEvent: '2026-08-01',
  requiresContestabilityReview: false,
} as unknown as ClaimView;

type Roles = Parameters<typeof claimSections>[0]['roles'];

function sectionsFor(
  overrides: Partial<Parameters<typeof claimSections>[0]> = {},
): ReturnType<typeof claimSections> {
  return claimSections({
    claim: CLAIM,
    claimId: 'c-1',
    roles: {} as Roles,
    canAssess: false,
    canDecide: false,
    canReopen: false,
    canSeeReinsurance: false,
    ...overrides,
  });
}

function everySection() {
  return sectionsFor({
    canAssess: true,
    canDecide: true,
    canReopen: true,
    canSeeReinsurance: true,
    roles: { CLAIMS_ASSESSOR: true, CLAIMS_MANAGER: true, FINANCE_OFFICER: true } as Roles,
  });
}

describe('claimSections', () => {
  /**
   * THE INVARIANT THE WHOLE SECTION BAR RESTS ON. The bar renders one link per entry pointing
   * at `#id`, and the panel it must land on carries that same id. They are written out seven
   * times by hand, so one typo in either half produces a link that scrolls nowhere -- and a
   * broken in-page anchor fails completely silently, with no console error and nothing on
   * screen to notice.
   */
  it('gives every section a panel whose id matches the link that targets it', () => {
    const sections = everySection();
    expect(sections.length).toBeGreaterThan(3);
    for (const section of sections) {
      expect(isValidElement(section.content)).toBe(true);
      expect((section.content as { props: { id?: string } }).props.id).toBe(section.id);
    }
  });

  it('never repeats an id, which would leave one anchor unreachable', () => {
    const ids = everySection().map((s) => s.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  /**
   * The gate this lane must not have widened. A staff.underwriter holds neither claims role, so
   * the record is evidence and nothing else -- and one section renders no bar at all, because a
   * bar with a single link jumps you where you already are.
   */
  it('shows an identity with no claims role only the evidence', () => {
    expect(sectionsFor().map((s) => s.id)).toEqual(['evidence']);
  });

  it('shows an assessor their own form and the history, but never the decision', () => {
    const ids = sectionsFor({
      canAssess: true,
      roles: { CLAIMS_ASSESSOR: true } as Roles,
    }).map((s) => s.id);
    expect(ids).toContain('assessment');
    expect(ids).toContain('assessments');
    expect(ids).not.toContain('settlement');
  });

  it('shows a manager the decision and the history, but never the assessment form', () => {
    const ids = sectionsFor({
      canDecide: true,
      roles: { CLAIMS_MANAGER: true } as Roles,
    }).map((s) => s.id);
    expect(ids).toContain('settlement');
    expect(ids).toContain('assessments');
    expect(ids).not.toContain('assessment');
  });

  /**
   * Payment appears only once there is money to report on. A REGISTERED claim has none, so a
   * finance officer looking at one gets evidence and nothing else.
   */
  it('withholds payment until the claim has reached a paying status', () => {
    const roles = { FINANCE_OFFICER: true } as Roles;
    expect(sectionsFor({ roles }).map((s) => s.id)).not.toContain('payment');
    expect(
      sectionsFor({
        roles,
        claim: { ...CLAIM, status: 'APPROVED' } as ClaimView,
      }).map((s) => s.id),
    ).toContain('payment');
  });

  /**
   * "Assessment" and "Assessments" sitting next to each other in the bar are two words apart
   * for a reader and one substring apart for every Playwright locator that looks for either --
   * which is why the history is labelled History while its panel keeps the heading Assessments.
   * Asserted as a rule rather than as those two literals, so the next label added obeys it too.
   */
  it('gives no section a label that is a substring of another', () => {
    const labels = everySection().map((s) => s.label.toLowerCase());
    for (const a of labels) {
      for (const b of labels) {
        if (a !== b) expect(b.includes(a)).toBe(false);
      }
    }
  });
});
