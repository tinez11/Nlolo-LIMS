import { useEffect, useId, useState } from 'react';
import { listCasesAwaitingIssue } from '@/api/underwriting';
import type { UnderwritingCaseView } from '@/api/types';
import { PartyName } from '@/components/PartyName';
import { Input, Select } from '@/components/ui/input';
import { matchCount, searchEnter } from '@/lib/searchKeys';
import { useFieldControl } from './fieldControl';

/**
 * Choose the decided underwriting case a manual issuance is made against.
 *
 * ## Only cases no policy has come from (2026-10-08)
 *
 * This listed the newest 100 DECIDED cases. Every case ever decided stays DECIDED, so on a working
 * platform nearly all of them already had their policy -- 1,416 of 1,426 on the dev database -- and the
 * few still waiting fell outside the hundred. `GET /underwriting/cases/awaiting-issue` answers the real
 * question: decided, and its sale not yet locked (issuing a policy locks it). Declined cases are listed,
 * because an underwriting override issues against one; members' evidence cases are not.
 *
 * The search narrows by proposal number on the server, so a case beyond the first page is still found.
 *
 * ## What a row shows
 *
 * The proposal number first, because it is the case's human key — `PRO-XXXXXXXX`, unique
 * per tenant, and the thing staff quote to each other. A native `<option>` holds text
 * only, so the applicant is named underneath the field once a case is chosen, where
 * {@link PartyName} can resolve it properly.
 */
export function UnderwritingCasePicker({
  value,
  onChange,
}: {
  value: string | null;
  /** The whole case, so the caller can prefill from it. Null when the selection is cleared. */
  onChange: (caseId: string | null, decidedCase: UnderwritingCaseView | null) => void;
}) {
  const [cases, setCases] = useState<UnderwritingCaseView[]>([]);
  const [total, setTotal] = useState(0);
  const [status, setStatus] = useState<'loading' | 'success' | 'error'>('loading');
  const [query, setQuery] = useState('');
  // Kept apart from the list, so narrowing the search does not lose the case already chosen.
  const [chosen, setChosen] = useState<UnderwritingCaseView | null>(null);
  const { id: fieldId } = useFieldControl();
  const searchId = useId();

  useEffect(() => {
    let cancelled = false;
    // Debounced like PartyPicker: one request per pause in typing, not per keystroke.
    const timer = setTimeout(() => {
      listCasesAwaitingIssue(query)
        .then((page) => {
          if (cancelled) return;
          setCases(page.items);
          setTotal(page.page.totalElements);
          setStatus('success');
        })
        .catch(() => {
          if (!cancelled) setStatus('error');
        });
    }, query ? 300 : 0);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [query]);

  function choose(decidedCase: UnderwritingCaseView) {
    setChosen(decidedCase);
    onChange(decidedCase.caseId ?? null, decidedCase);
  }

  const selected = value ?(cases.find((c) => c.caseId === value) ?? (chosen?.caseId === value ? chosen : null)) : null;
  const options = selected && !cases.some((c) => c.caseId === selected.caseId) ? [selected, ...cases] : cases;

  if (status === 'error') {
    return <p className="text-xs text-status-danger-fg">Could not load underwriting cases.</p>;
  }

  return (
    <div className="space-y-1">
      {/* Its own id: inside a FormField a control without one takes the field's, and the field's label
          must stay on the select below. */}
      <Input
        id={searchId}
        inputSize="sm"
        placeholder="Search by proposal number…"
        aria-label="Search cases by proposal number"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={searchEnter(() => {
          if (query.trim() && cases.length === 1 && cases[0]) choose(cases[0]);
        })}
      />
      {query.trim() !== '' && status === 'success' && cases.length > 0 && (
        <p className="text-xs text-subtle-foreground" role="status">
          {matchCount(total, 'case', 'cases')} — choose below{cases.length === 1 ? ', or press Enter' : ''}.
        </p>
      )}
      <Select
        id={fieldId}
        value={value ?? ''}
        onChange={(e) => {
          const caseId = e.target.value;
          const decidedCase = options.find((c) => c.caseId === caseId) ?? null;
          if (decidedCase) choose(decidedCase);
          else { setChosen(null); onChange(null, null); }
        }}
      >
        <option value="">{status === 'success' ? 'Select a decided underwriting case' : 'Loading cases awaiting issue…'}</option>
        {options.map((c) => (
          <option key={c.caseId} value={c.caseId}>
            {c.proposalNumber ?? c.caseId} — {c.decisionOutcome ?? 'no decision'}
          </option>
        ))}
      </Select>

      {status === 'success' && cases.length === 0 && (
        // Not an error: every decided case already has its policy, or the search matches none.
        <p className="text-xs text-muted-foreground">
          {query.trim()
            ? `No case awaiting issue matches “${query.trim()}”.`
            : 'No decided case is waiting for a policy. A case appears here once an underwriter has decided it.'}
        </p>
      )}
      {total > cases.length && (
        <p className="text-xs text-subtle-foreground">
          Showing the newest {cases.length} of {total} — search by proposal number to find an older one.
        </p>
      )}

      {selected && (
        <p className="text-xs text-muted-foreground">
          Applicant:{' '}
          {selected.applicantPartyId ? <PartyName partyId={selected.applicantPartyId} /> : '—'}
          {selected.decisionOverrodeRecommendation && (
            <span className="ml-2 text-status-warning-fg">
              Decided against the recommendation by a senior underwriter
            </span>
          )}
        </p>
      )}
    </div>
  );
}
