import type { ReactNode } from 'react';
import type { ClaimView } from '@/api/types';
import type { staffRoles } from '@/auth/claims';
import { Panel } from '@/components/Panel';
import { AssessmentHistoryPanel } from './AssessmentHistoryPanel';
import { ClaimAssessmentPanel } from './ClaimAssessmentPanel';
import { ClaimPaymentPanel } from './ClaimPaymentPanel';
import { ClaimReopenPanel } from './ClaimReopenPanel';
import { ClaimSettlementPanel } from './ClaimSettlementPanel';
import { DocumentRequestsPanel } from './DocumentRequestsPanel';
import { EvidencePanel } from './EvidencePanel';
import { RecoveriesPanel } from '@/features/reinsurance/RecoveriesPanel';

/** One section of the claim record: its jump-link, and the panel that link lands on. */
export interface Section {
  id: string;
  label: string;
  content: ReactNode;
}

/**
 * The claim record's sections, in the order the person who opened the page needs them.
 *
 * One array rather than a `sections` list beside a stack of conditional panels. Five of these
 * panels are gated on role and on status, so two lists would drift the first time a gate
 * changed and the bar would offer a jump to a panel that is not on the page -- a link that
 * scrolls nowhere, which is worse than no link. Returning the content beside the label makes
 * that impossible to express.
 */
export function claimSections({
  claim,
  claimId,
  roles,
  canAssess,
  canDecide,
  canReopen,
  canSeeReinsurance,
}: {
  claim: ClaimView;
  claimId: string;
  roles: ReturnType<typeof staffRoles>;
  canAssess: boolean;
  canDecide: boolean;
  canReopen: boolean;
  canSeeReinsurance: boolean;
}): Section[] {
  const sections: Section[] = [];

  // Status-gated, so in practice one of the three is present at a time --
  // REGISTERED/UNDER_ASSESSMENT for the first two, REJECTED/SETTLED for the last. Where an
  // assessor-and-manager sees two, the subtitles say which is which; both genuinely are the
  // point of the page.
  if (canAssess) {
    sections.push({
      id: 'assessment',
      label: 'Assessment',
      content: (
        <Panel
          id="assessment"
          emphasis
          title="Submit an assessment"
          subtitle="Claims may carry more than one before a decision is made."
        >
          <ClaimAssessmentPanel claimId={claimId} />
        </Panel>
      ),
    });
  }

  if (canDecide) {
    sections.push({
      id: 'settlement',
      label: 'Settlement',
      content: (
        <Panel
          id="settlement"
          emphasis
          title="Decide settlement"
          subtitle="Approve or reject — distinct from assessing."
        >
          <ClaimSettlementPanel
            claimId={claimId}
            policyNumber={claim.policyNumber}
            onScheme={!!claim.policyMemberId}
          />
        </Panel>
      ),
    });
  }

  if (canReopen) {
    sections.push({
      id: 'reopen',
      label: 'Reopen',
      content: (
        <Panel id="reopen" emphasis title="Reopen">
          <ClaimReopenPanel claimId={claimId} wasSettled={claim.status === 'SETTLED'} />
        </Panel>
      ),
    });
  }

  // Above Evidence, and shown to assessors and managers alike. The manager deciding this claim
  // did not assess it -- the platform forbids it -- so this is the only place their
  // colleague's reasoning appears. It sits next to the decision form for that reason, not at
  // the bottom with the attachments.
  //
  // Labelled "History" in the bar, not "Assessments": the bar would otherwise carry both
  // "Assessment" and "Assessments", which is two words apart for a reader scanning it and one
  // substring apart for every Playwright locator that looks for either.
  if (roles.CLAIMS_ASSESSOR || roles.CLAIMS_MANAGER) {
    sections.push({
      id: 'assessments',
      label: 'History',
      content: (
        <Panel
          id="assessments"
          title="Assessments"
          subtitle="What the assessors found, and what they recommended paying"
        >
          <AssessmentHistoryPanel claimId={claimId} />
        </Panel>
      ),
    });
  }

  // Once approved, where the money is. Shown to the claims staff who decided it and to finance
  // who pay it; payee and rail are internal, so not to anybody else.
  if (
    (roles.CLAIMS_ASSESSOR || roles.CLAIMS_MANAGER || roles.FINANCE_OFFICER) &&
    (claim.status === 'APPROVED' ||
      claim.status === 'SETTLEMENT_REQUESTED' ||
      claim.status === 'SETTLED' ||
      claim.status === 'REOPENED')
  ) {
    sections.push({
      id: 'payment',
      label: 'Payment',
      content: (
        <Panel
          id="payment"
          title="Payment"
          subtitle="Where the settlement is, and who it is waiting on"
        >
          <ClaimPaymentPanel claimId={claimId} claimStatus={claim.status} />
        </Panel>
      ),
    });
  }

  // Claims people's own working list -- like the assessments, not shown to a session with no claims role.
  if (roles.CLAIMS_ASSESSOR || roles.CLAIMS_MANAGER) {
    sections.push({
      id: 'document-requests',
      label: 'Requests',
      content: (
        <Panel
          id="document-requests"
          title="Documents requested"
          subtitle="What the claimant has been asked for; they answer from the customer portal"
        >
          <DocumentRequestsPanel claimId={claimId} canRequest={claim.status !== 'SETTLED'} />
        </Panel>
      ),
    });
  }

  sections.push({
    id: 'evidence',
    label: 'Evidence',
    content: (
      <Panel
        id="evidence"
        title="Evidence"
        subtitle="Photos, certificates, and reports attached to this claim"
      >
        <EvidencePanel claimId={claimId} canAttach={claim.status !== 'SETTLED'} />
      </Panel>
    ),
  });

  // Moved out of the 320px rail: a recoveries table needs the width, and nothing in it is a
  // fact about the claim itself.
  if (canSeeReinsurance) {
    sections.push({
      id: 'reinsurance',
      label: 'Reinsurance',
      content: (
        <Panel
          id="reinsurance"
          title="Reinsurance"
          subtitle="Recoveries this claim's own settlement produced"
        >
          <RecoveriesPanel claimId={claimId} />
        </Panel>
      ),
    });
  }

  return sections;
}

