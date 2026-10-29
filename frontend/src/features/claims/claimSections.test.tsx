import { isValidElement } from 'react';
import { describe, expect, it } from 'vitest';
import { grantRoles } from '@/test/access';
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
 * this lane -- seven role- and status-gated entries, each a tab and the panels it shows -- and
 * every one of its failure modes is silent on screen.
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

/** True when the element tree holds a panel with this id. */
function holdsPanel(node: unknown, id: string): boolean {
  if (Array.isArray(node)) return node.some((n) => holdsPanel(n, id));
  if (!isValidElement(node)) return false;
  const props = node.props as { id?: string; children?: unknown };
  return props.id === id || holdsPanel(props.children, id);
}

describe('claimSections', () => {
  /** Every section draws something, and no two share a tab value -- which would leave one tab unreachable. */
  it('gives every section content and a tab value of its own', () => {
    const sections = everySection();
    expect(sections.length).toBeGreaterThan(3);
    for (const section of sections) expect(isValidElement(section.content)).toBe(true);
    const ids = sections.map((s) => s.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  /**
   * The gate this lane must not have widened. A staff.underwriter holds neither claims role, so
   * the record is evidence and nothing else -- and one section renders no tab strip at all.
   */
  it('shows an identity with no claims role only the evidence', () => {
    expect(sectionsFor().map((s) => s.id)).toEqual(['evidence']);
  });

  /**
   * Tabbed since 2026-10-29: the assessors' findings ride inside the tab where the person acts, so a manager deciding
   * reads them beside the decision -- not a tab away. History is its own tab only when there is nothing to act on.
   */
  it("puts the history under an assessor's form, and never offers them the decision", () => {
    const sections = sectionsFor({ canAssess: true, roles: { CLAIMS_ASSESSOR: true } as Roles });
    const ids = sections.map((s) => s.id);
    expect(ids).toContain('assessment');
    expect(ids).not.toContain('settlement');
    expect(ids).not.toContain('assessments');
    expect(holdsPanel(sections.find((s) => s.id === 'assessment')!.content, 'assessments')).toBe(true);
  });

  it("puts the history under a manager's decision, and never offers them the assessment form", () => {
    const sections = sectionsFor({ canDecide: true, roles: { CLAIMS_MANAGER: true } as Roles });
    const ids = sections.map((s) => s.id);
    expect(ids).toContain('settlement');
    expect(ids).not.toContain('assessment');
    expect(ids).not.toContain('assessments');
    expect(holdsPanel(sections.find((s) => s.id === 'settlement')!.content, 'assessments')).toBe(true);
  });

  it('gives the history a tab of its own once there is nothing to act on', () => {
    const ids = sectionsFor({ roles: { CLAIMS_MANAGER: true } as Roles }).map((s) => s.id);
    expect(ids).toContain('assessments');
  });

  /**
   * Payment appears only once there is money to report on. A REGISTERED claim has none, so a
   * finance officer looking at one gets evidence and nothing else.
   */
  it('withholds payment until the claim has reached a paying status', () => {
    // Finance sees where the money is through payouts.view (2026-10-26), not the FINANCE_OFFICER flag.
    grantRoles('FINANCE_OFFICER');
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
