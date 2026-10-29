import { Check, Circle, CircleDot, Paperclip } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { attachClaimEvidence } from '@/api/claims';
import { getCustomerClaim, type CustomerClaimView } from '@/api/portal';
import { Field } from '@/components/Field';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';
import { cn } from '@/lib/cn';
import { formatDate, formatInstant } from '@/lib/dates';
import { money } from './customerText';
import { useWords } from './words';

import { EVIDENCE_ACCEPT } from './customerText';

/**
 * One of the customer's claims (2026-10-08, the customer portal design step 4; PRD §19-21): where it is, step by step;
 * the decision in plain words; what we still need and a place to send it; what they have sent. Read from
 * `/customer/claims/{id}`, which refuses a claim somebody else made and carries nothing only staff should see.
 */
export function CustomerClaimPage() {
  const words = useWords().portal;
  const w = words.claim;
  const { claimId = '' } = useParams();
  const [claim, setClaim] = useState<CustomerClaimView | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let live = true;
    getCustomerClaim(claimId).then(
      (c) => { if (live) { setClaim(c); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [claimId, reload]);

  const refresh = () => setReload((n) => n + 1);
  const breadcrumb = [{ label: words.claims.title, to: '/customers/claims' }];
  if (error) {
    return (
      <>
        <PageHeader breadcrumb={breadcrumb} title={w.details} />
        <div className="px-4 pt-4 sm:px-6"><ErrorPanel error={error} onRetry={refresh} /></div>
      </>
    );
  }
  if (!claim) return <LoadingBlock label="Loading your claim" />;

  const open = claim.requests.filter((r) => r.status === 'OPEN');
  const settled = claim.status === 'SETTLED';
  const type = words.claimType[claim.claimType] ?? claim.claimType;
  return (
    <>
      <PageHeader breadcrumb={breadcrumb} title={w.claim(type)}
        description={<span>{claim.productName ?? ''} <span className="font-mono text-xs">{claim.policyNumber}</span></span>}
        status={<span className="rounded-full bg-control px-2.5 py-0.5 text-sm font-medium">{words.claimStatus[claim.status] ?? claim.statusText}</span>} />
      <div className="w-full max-w-3xl space-y-4 px-4 pb-10 text-base sm:px-6">
        {open.length > 0 && (
          <Panel title={w.need} subtitle={w.needLead} emphasis>
            <ul className="divide-y divide-border">
              {open.map((r) => (
                <li key={r.requestId} className="space-y-2 px-4 py-3">
                  <div>
                    <p className="font-semibold">{r.document}</p>
                    {r.reason && <p className="text-sm text-muted-foreground">{r.reason}</p>}
                    <p className="text-sm text-subtle-foreground">{w.asked(formatInstant(r.requestedAt))}</p>
                  </div>
                  <SendDocument claimId={claim.claimId} requestId={r.requestId} description={r.document} onSent={refresh}
                    label={w.send(r.document)} />
                </li>
              ))}
            </ul>
          </Panel>
        )}

        <Panel title={w.progress}>
          <ol className="space-y-3 px-4 py-3">
            {claim.steps.map((s) => (
              <li key={s.label} className="flex items-start gap-3">
                <StepIcon state={s.state} />
                <span>
                  <span className={cn(s.state === 'PENDING' && 'text-muted-foreground', s.state === 'CURRENT' && 'font-medium')}>
                    {s.label}
                  </span>
                  {s.date && s.state !== 'PENDING' && (
                    <span className="block text-sm text-muted-foreground">{formatInstant(s.date)}</span>
                  )}
                </span>
              </li>
            ))}
          </ol>
          {claim.decision && <p className="border-t border-border px-4 py-3">{claim.decision}</p>}
        </Panel>

        <Panel title={w.details}>
          <dl className="px-4 pb-2">
            <Field label={w.type} value={type} />
            <Field label={w.date} value={formatDate(claim.dateOfEvent)} />
            {claim.approvedAmount != null && <Field label={w.approved} value={money(claim.approvedAmount, claim.currency)} emphasis />}
          </dl>
        </Panel>

        <Panel title={w.sent}>
          {claim.documents.length === 0 ? (
            <p className="px-4 py-3 text-muted-foreground">{w.noDocs}</p>
          ) : (
            <ul className="divide-y divide-border">
              {claim.documents.map((d) => (
                <li key={d.documentRef} className="flex items-center gap-2 px-4 py-2.5 text-sm">
                  <Paperclip className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                  <span className="min-w-0">
                    <span className="block truncate">{d.description || w.document}</span>
                    <span className="block text-xs text-muted-foreground">{formatInstant(d.uploadedAt)}</span>
                  </span>
                </li>
              ))}
            </ul>
          )}
          {!settled && (
            <div className="border-t border-border px-4 py-3">
              <SendDocument claimId={claim.claimId} onSent={refresh} label={w.another} withDescription />
            </div>
          )}
        </Panel>
      </div>
    </>
  );
}

function StepIcon({ state }: { state: string }) {
  if (state === 'DONE') {
    return <span className="mt-0.5 rounded-full bg-status-success-fg p-0.5 text-background"><Check className="size-3" aria-label="Done" /></span>;
  }
  if (state === 'CURRENT') return <CircleDot className="mt-0.5 size-4 text-accent" aria-label="In progress" />;
  return <Circle className="mt-0.5 size-4 text-subtle-foreground" aria-label="Not yet" />;
}

/** Choose a file and send it -- against a request when one is named, which marks that request received. */
function SendDocument({ claimId, requestId, description, label, withDescription = false, onSent }: {
  claimId: string; requestId?: string; description?: string; label: string; withDescription?: boolean; onSent: () => void;
}) {
  const w = useWords().portal.claim;
  const [file, setFile] = useState<File | null>(null);
  const [note, setNote] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function send() {
    if (!file) return;
    setSending(true);
    setError(null);
    try {
      await attachClaimEvidence(claimId, file, (withDescription ? note.trim() : description) || undefined, requestId);
      setFile(null);
      setNote('');
      onSent();
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-end gap-2">
        <FormField label={withDescription ? w.file : label} className="min-w-0 flex-1">
          <Input type="file" accept={EVIDENCE_ACCEPT} onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        </FormField>
        {withDescription && (
          <FormField label={w.what} className="min-w-0 flex-1">
            <Input value={note} onChange={(e) => setNote(e.target.value)} placeholder={w.whatPlaceholder} />
          </FormField>
        )}
        <Button variant="primary" className="h-11" onClick={() => void send()} disabled={!file} pending={sending}>{w.sendBtn}</Button>
      </div>
      {error && <InlineError error={error} lead={w.failed} />}
    </div>
  );
}
