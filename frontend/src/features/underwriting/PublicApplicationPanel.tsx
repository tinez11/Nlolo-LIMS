import { TriangleAlert } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getCasePublicApplication, type PublicApplicationView } from '@/api/publicQuote';
import { Field } from '@/components/Field';
import { InlineError } from '@/components/InlineError';
import { Panel } from '@/components/Panel';
import { formatInstant } from '@/lib/dates';
import { toApiError, type ApiError } from '@/lib/apiError';

/**
 * A case that came through the public quote link (2026-11-08): the phone a texted code confirmed, the declaration the
 * applicant agreed to word for word, and -- first, because it changes what to do -- anyone already on the books who may
 * be the same person. Never merged (the user's decision): the underwriter looks, and staff join records by hand if so.
 */
export function PublicApplicationPanel({ caseId }: { caseId: string }) {
  const [result, setResult] = useState<{ for: string; view: PublicApplicationView | null; error: ApiError | null } | null>(null);

  useEffect(() => {
    let live = true;
    getCasePublicApplication(caseId).then(
      (view) => { if (live) setResult({ for: caseId, view, error: null }); },
      (e: unknown) => { if (live) setResult({ for: caseId, view: null, error: toApiError(e) }); },
    );
    return () => { live = false; };
  }, [caseId]);

  const current = result?.for === caseId ? result : null;
  if (!current) return null;
  if (current.error) {
    return <Panel title="Applied online"><div className="px-4 pb-4"><InlineError error={current.error} /></div></Panel>;
  }
  const v = current.view!;
  const matches = v.possibleExistingClients;
  return (
    <Panel title="Applied online">
      <div className="space-y-3 px-4 pb-4">
        {matches.length > 0 && (
          <div role="alert" className="rounded-md border border-status-warning-fg/40 bg-status-warning-bg p-3 text-sm text-status-warning-fg">
            <p className="flex items-center gap-2 font-medium">
              <TriangleAlert className="size-4" aria-hidden />
              {matches.length === 1 ? 'Possible existing client' : `${matches.length} possible existing clients`}
            </p>
            <p className="mt-1 text-foreground">
              Same phone, or same name and date of birth.
            </p>
            <ul className="mt-2 space-y-1 text-foreground">
              {matches.map((m) => (
                <li key={m.partyId}>
                  <Link to={`/staff/parties/${m.partyId}`} className="underline">{m.displayName ?? m.partyId}</Link>
                </li>
              ))}
            </ul>
          </div>
        )}
        <dl>
          <Field label="Phone confirmed by text" value={v.phoneNumber} />
          <Field label="Applied" value={formatInstant(v.appliedAt)} />
          <Field label="Declaration" value={`Agreed, read in ${v.language === 'sw' ? 'Swahili' : 'English'}`}
            note={`Wording ${v.consentVersion}`} />
        </dl>
        <details className="text-sm">
          <summary className="cursor-pointer font-medium">The declaration as they read it</summary>
          <p className="mt-2 whitespace-pre-line text-muted-foreground">{v.consentText}</p>
        </details>
      </div>
    </Panel>
  );
}
