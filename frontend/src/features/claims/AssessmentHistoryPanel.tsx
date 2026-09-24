import { useEffect } from 'react';
import { formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectAssessments, selectClaimableCover, useClaimStore } from '@/store/claimStore';
import { recommendationExceedsCover } from './settlementDecisionForm';

/**
 * What the assessors found, newest first.
 *
 * ## Why this did not exist
 *
 * Assessments were write-only. `POST /claims/{id}/assessments` persisted them, two repository
 * methods counted them and checked who wrote them, and
 * `findByClaimIdAndTenantIdOrderByCreatedAtDesc` sat there with no caller at all.
 *
 * That is not a missing nicety. Separation of duties means the person deciding a settlement is
 * NEVER the person who assessed it -- `ClaimsApiImpl` checks the persisted assessor identity, not
 * just the role claim. So the decision-maker is by construction someone who did not see the
 * evidence, and had no way to read what their colleague concluded. They were shown an empty
 * amount field and asked for a number.
 *
 * The findings matter as much as the figure. An amount alone says what to pay; the findings say
 * why, and are the only thing on this page that can justify departing from them.
 */
export function AssessmentHistoryPanel({ claimId }: { claimId: string }) {
  const loadAssessments = useClaimStore((s) => s.loadAssessments);
  const assessments = useClaimStore(selectAssessments(claimId));
  // Read, never loaded, here: the assessment and settlement panels beside this one already
  // load it, and the store does not deduplicate a second request. Absent, the mark below
  // simply does not render -- nothing is claimed about a ceiling nobody has read.
  const cover = useClaimStore(selectClaimableCover(claimId)).data?.claimableCover ?? null;

  useEffect(() => {
    void loadAssessments(claimId);
  }, [claimId, loadAssessments]);

  if (isInitialLoad(assessments)) {
    return <LoadingBlock label="Loading assessments" />;
  }

  if (assessments.data === null && assessments.status === 'error' && assessments.error) {
    return <ErrorPanel error={assessments.error} onRetry={() => void loadAssessments(claimId)} />;
  }

  const rows = assessments.data ?? [];

  if (rows.length === 0) {
    return (
      <EmptyState
        title="Not assessed yet"
        // Says which claims legitimately have none, so an empty panel is not read
        // as a failure to load.
        description="A claim is assessed before it can be approved — except MATURITY, which approves straight from registration."
      />
    );
  }

  return (
    <ul className="divide-y divide-border">
      {rows.map((assessment) => (
        <li key={assessment.claimAssessmentId} className="space-y-1 px-4 py-3">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <span className="text-sm font-medium">
              {formatMoney(assessment.recommendedAmount)} recommended
            </span>
            <span className="text-xs text-muted-foreground">
              {assessment.assessor} · {formatInstant(assessment.createdAt)}
            </span>
          </div>
          <p className="text-xs text-muted-foreground">{assessment.findings}</p>
          {/* Recorded before the backend bounded recommendations. Kept on the record as
              written -- an assessment is evidence and is not rewritten -- but flagged, since no
              approval can follow it. */}
          {cover && recommendationExceedsCover(assessment.recommendedAmount, cover) && (
            <p className="text-xs font-medium text-status-warning-fg">
              Exceeds the {formatMoney(cover)} this claim is covered for, so it cannot be approved
              as recommended.
            </p>
          )}
          {/*
            Shown only when true, and worded as what it is. A fraud indicator is a
            SCRUTINY SIGNAL and never blocks approval -- that is stated in the
            aggregate design, in the OpenAPI description and again in
            ClaimsApiImpl. Rendering it as a refusal would misrepresent a control
            that deliberately does not refuse anything.
          */}
          {assessment.fraudIndicator && (
            <p className="text-xs font-medium text-status-warning-fg">
              Flagged for fraud scrutiny — this does not block approval, it asks for a closer look.
            </p>
          )}
        </li>
      ))}
    </ul>
  );
}
