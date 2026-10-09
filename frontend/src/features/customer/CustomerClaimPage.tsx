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
import { claimTypeText, money } from './customerText';

import { EVIDENCE_ACCEPT } from './customerText';

/**
 * One of the customer's claims (2026-10-08, the customer portal design step 4; PRD §19-21): where it is, step by step;
 * the decision in plain words; what we still need and a place to send it; what they have sent. Read from
 * `/customer/claims/{id}`, which refuses a claim somebody else made and carries nothing only staff should see.
 */
export function CustomerClaimPage() {
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
  const breadcrumb = [{ label: 'My claims', to: '/customers/claims' }];
  if (error) {
    return (
      <>
        <PageHeader breadcrumb={breadcrumb} title="Claim" />
        <div className="px-4 pt-4 sm:px-6"><ErrorPanel error={error} onRetry={refresh} /></div>
      </>
    );
  }
  if (!claim) return <LoadingBlock label="Loading your claim" />;

  const open = claim.requests.filter((r) => r.status === 'OPEN');
  const settled = claim.status === 'SETTLED';
  return (
    <>
      <PageHeader breadcrumb={breadcrumb} title={`${claimTypeText(claim.claimType)} claim`}
        description={<span>{claim.productName ?? ''} <span className="font-mono text-xs">{claim.policyNumber}</span></span>}
        status={<span className="rounded-full bg-control px-2 py-0.5 text-xs font-medium">{claim.statusText}</span>} />
      <div className="space-y-4 px-4 pb-8 sm:px-6">
        {open.length > 0 && (
          <Panel title="We need from you" subtitle="Send each document here. A clear photo or a PDF is fine." emphasis>
            <ul className="divide-y divide-border">
              {open.map((r) => (
                <li key={r.requestId} className="space-y-2 px-4 py-3">
                  <div>
                    <p className="text-sm font-medium">{r.document}</p>
                    {r.reason && <p className="text-xs text-muted-foreground">{r.reason}</p>}
                    <p className="text-xs text-subtle-foreground">Asked {formatInstant(r.requestedAt)}</p>
                  </div>
                  <SendDocument claimId={claim.claimId} requestId={r.requestId} description={r.document} onSent={refresh}
                    label={`Send ${r.document}`} />
                </li>
              ))}
            </ul>
          </Panel>
        )}

        <Panel title="Progress">
          <ol className="space-y-3 px-4 py-3">
            {claim.steps.map((s) => (
              <li key={s.label} className="flex items-start gap-3">
                <StepIcon state={s.state} />
                <span className="text-sm">
                  <span className={cn(s.state === 'PENDING' && 'text-muted-foreground', s.state === 'CURRENT' && 'font-medium')}>
                    {s.label}
                  </span>
                  {s.date && s.state !== 'PENDING' && (
                    <span className="block text-xs text-muted-foreground">{formatInstant(s.date)}</span>
                  )}
                </span>
              </li>
            ))}
          </ol>
          {claim.decision && <p className="border-t border-border px-4 py-3 text-sm">{claim.decision}</p>}
        </Panel>

        <Panel title="Claim">
          <dl className="px-4 pb-2">
            <Field label="Type" value={claimTypeText(claim.claimType)} />
            <Field label="Date of event" value={formatDate(claim.dateOfEvent)} />
            {claim.approvedAmount != null && <Field label="Amount approved" value={money(claim.approvedAmount, claim.currency)} emphasis />}
          </dl>
        </Panel>

        <Panel title="Documents you sent">
          {claim.documents.length === 0 ? (
            <p className="px-4 py-3 text-xs text-muted-foreground">No documents yet.</p>
          ) : (
            <ul className="divide-y divide-border">
              {claim.documents.map((d) => (
                <li key={d.documentRef} className="flex items-center gap-2 px-4 py-2.5 text-sm">
                  <Paperclip className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                  <span className="min-w-0">
                    <span className="block truncate">{d.description || 'Document'}</span>
                    <span className="block text-xs text-muted-foreground">{formatInstant(d.uploadedAt)}</span>
                  </span>
                </li>
              ))}
            </ul>
          )}
          {!settled && (
            <div className="border-t border-border px-4 py-3">
              <SendDocument claimId={claim.claimId} onSent={refresh} label="Send another document" withDescription />
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
        <FormField label={withDescription ? 'File' : label} className="min-w-0 flex-1">
          <Input type="file" accept={EVIDENCE_ACCEPT} onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        </FormField>
        {withDescription && (
          <FormField label="What is it?" className="min-w-0 flex-1">
            <Input value={note} onChange={(e) => setNote(e.target.value)} placeholder="e.g. Hospital discharge letter" />
          </FormField>
        )}
        <Button variant="primary" onClick={() => void send()} disabled={!file} pending={sending}>Send</Button>
      </div>
      {error && <InlineError error={error} lead="Could not send the document" />}
    </div>
  );
}
