import { useEffect, useState } from 'react';
import { listCases } from '@/api/underwriting';
import type { UnderwritingCaseView } from '@/api/types';
import { PartyName } from '@/components/PartyName';
import { Select } from '@/components/ui/input';
import { useFieldControl } from './fieldControl';

/**
 * Choose the decided underwriting case a manual issuance is made against.
 *
 * ## Why a select rather than a typeahead like {@link PartyName}'s sibling PartyPicker
 *
 * `GET /underwriting/cases` has no free-text search — its only filters are `status` and
 * `applicantPartyId`. A search box over an endpoint that cannot search would either be a
 * lie or a client-side filter over a page of results pretending to be one. So this loads
 * the decided cases and lists them, which is what the API actually offers.
 *
 * That is workable because the set is small by construction: a case leaves this list the
 * moment it is issued, since a case with a policy is refused by the server. What remains
 * is decided-but-not-issued — the backlog this screen exists to clear. If that list ever
 * grows past a page, the fix is a search parameter on the endpoint, not a cleverer client.
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
  const [status, setStatus] = useState<'loading' | 'success' | 'error'>('loading');
  const { id: fieldId } = useFieldControl();

  useEffect(() => {
    let cancelled = false;
    listCases({ status: 'DECIDED', pageSize: 100 })
      .then((page) => {
        if (cancelled) return;
        setCases(page.items);
        setStatus('success');
      })
      .catch(() => {
        if (!cancelled) setStatus('error');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Derived, not stored: the lint here bans synchronous setState in an effect body, and a
  // second copy of the selection would only be able to disagree with `value`.
  const selected = cases.find((c) => c.caseId === value) ?? null;

  if (status === 'loading') {
    return <p className="text-xs text-muted-foreground">Loading decided cases…</p>;
  }
  if (status === 'error') {
    return <p className="text-xs text-status-danger-fg">Could not load underwriting cases.</p>;
  }

  return (
    <div className="space-y-1">
      <Select
        id={fieldId}
        value={value ?? ''}
        onChange={(e) => {
          const caseId = e.target.value;
          onChange(caseId || null, cases.find((c) => c.caseId === caseId) ?? null);
        }}
      >
        <option value="">Select a decided underwriting case</option>
        {cases.map((c) => (
          <option key={c.caseId} value={c.caseId}>
            {c.proposalNumber ?? c.caseId} — {c.decisionOutcome ?? 'no decision'}
          </option>
        ))}
      </Select>

      {cases.length === 0 && (
        // Not an error. It is the ordinary state of a platform with nothing waiting to be
        // issued by hand, and saying so beats an empty dropdown the user has to interpret.
        <p className="text-[11px] text-muted-foreground">
          No decided cases are waiting to be issued. A case appears here once an underwriter
          has decided it, and leaves once its policy exists.
        </p>
      )}

      {selected && (
        <p className="text-[11px] text-muted-foreground">
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
