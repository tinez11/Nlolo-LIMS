import { Upload } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { replaceGroupSchedule } from '@/api/groupFuneral';
import type { GroupProposal, GroupScheduleResult } from '@/api/types';
import { getGroupProposal } from '@/api/underwriting';
import { InlineError } from '@/components/InlineError';
import { Panel } from '@/components/Panel';
import { Button } from '@/components/ui/button';
import { FUNERAL_ROLE_LABELS } from '@/features/products/funeralSchema';
import { fromLives } from '@/features/policies/groupFuneral';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate } from '@/lib/dates';

/**
 * A group funeral proposal on its case (2026-10-07): the plan and the families the underwriter is
 * deciding on, and -- until the case is decided -- the association's file to replace them, all or
 * nothing. Renders nothing for any other case.
 */
export function GroupFuneralProposalPanel({ caseId, canReplace }: { caseId: string; canReplace: boolean }) {
  const [proposal, setProposal] = useState<GroupProposal | null>(null);
  const [version, setVersion] = useState(0);
  const [result, setResult] = useState<GroupScheduleResult | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let live = true;
    getGroupProposal(caseId).then((p) => { if (live) setProposal(p); }, () => undefined);
    return () => { live = false; };
  }, [caseId, version]);

  if (!proposal || proposal.benefitBasis !== 'FUNERAL_PLAN') return null;
  const families = fromLives(proposal.lives ?? []);

  async function replace(file: File) {
    setError(null);
    try {
      const outcome = await replaceGroupSchedule(caseId, file);
      setResult(outcome);
      if (outcome.accepted) setVersion((v) => v + 1);
    } catch (e) {
      setError(toApiError(e));
    }
  }

  return (
    <Panel title="Group funeral proposal"
      subtitle={`Plan ${proposal.planCode ?? ''} · ${families.length} member${families.length === 1 ? '' : 's'}, billed members x the plan's group rate`}>
      {canReplace && (
        <div className="flex flex-wrap items-center gap-2 border-b border-border px-4 py-2.5 text-xs">
          <input ref={fileInput} type="file" accept=".csv,text/csv" className="hidden" aria-label="Replacement schedule file"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) void replace(f); e.target.value = ''; }} />
          <Button size="sm" variant="ghost" onClick={() => fileInput.current?.click()}>
            <Upload />
            Replace from file
          </Button>
          <span className="text-muted-foreground">Every family is checked by the plan; any problem changes nothing.</span>
        </div>
      )}
      {error && <div className="px-4 pt-3"><InlineError error={error} /></div>}
      {result && (
        <div className="px-4 pt-3 text-xs" aria-label="Schedule upload result">
          {result.accepted
            ? <p>Replaced: {result.families} families, {result.lives} lives.</p>
            : (
              <ul className="list-disc space-y-0.5 pl-5 text-status-danger-fg">
                {result.problems.map((p) => <li key={p}>{p}</li>)}
              </ul>
            )}
        </div>
      )}
      <div className="divide-y divide-border">
        {families.map((f) => (
          <div key={f.reference} className="px-4 py-2 text-xs" aria-label={`Proposed member ${f.reference}`}>
            <p>
              <strong>{f.reference} · {f.mainName}</strong> (born {formatDate(f.mainDateOfBirth)})
              {f.beneficiaryName && <span className="text-muted-foreground"> · beneficiary {f.beneficiaryName}</span>}
            </p>
            {f.dependants.length > 0 && (
              <p className="text-muted-foreground">
                {f.dependants.map((d) => `${d.fullName} (${FUNERAL_ROLE_LABELS[d.role].toLowerCase()}, born ${formatDate(d.dateOfBirth)})`).join('; ')}
              </p>
            )}
          </div>
        ))}
      </div>
    </Panel>
  );
}
