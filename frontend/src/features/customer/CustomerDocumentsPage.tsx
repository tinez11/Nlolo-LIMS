import { ChevronDown, ChevronRight, Download } from 'lucide-react';
import { useEffect, useState } from 'react';
import { downloadClaimEvidence, listClaimEvidence, searchClaims } from '@/api/claims';
import {
  documentFileName,
  downloadPaymentSchedule,
  downloadPolicySchedule,
  downloadReceipt,
  downloadSavingsStatement,
  listReceipts,
  type ReceiptLine,
} from '@/api/documents';
import { getCustomerDashboard, type CustomerPolicySummary } from '@/api/portal';
import type { ClaimEvidenceView, ClaimView } from '@/api/types';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { DownloadButtons } from '@/features/documents/DownloadButtons';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate, formatInstant } from '@/lib/dates';
import { saveBlob } from '@/lib/download';
import { categoryText, claimStatusText, money } from './customerText';

/**
 * The customer's documents (2026-10-08, the customer portal design step 3; PRD §29): for each policy its schedule,
 * premium schedule, savings statement where it has an account, and a receipt for every premium paid; and the
 * documents attached to their claims. Every file is the server's own rendering, for this customer's own policies.
 */
export function CustomerDocumentsPage() {
  const [policies, setPolicies] = useState<CustomerPolicySummary[] | null>(null);
  const [claims, setClaims] = useState<ClaimView[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let live = true;
    Promise.all([getCustomerDashboard(), searchClaims({ pageSize: 50 })]).then(
      ([d, c]) => { if (live) { setPolicies(d.policies); setClaims(c.items); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [reload]);

  return (
    <>
      <PageHeader title="My documents" description="Download your policy documents, premium schedules and receipts." />
      <div className="space-y-4 px-4 pb-8 sm:px-6">
        {error ? <ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} />
          : policies === null ? <LoadingBlock label="Loading your documents" />
            : policies.length === 0 ? <EmptyState title="No documents yet" description="Documents appear once you hold a policy." />
              : policies.map((p) => <PolicyDocuments key={p.policyNumber} policy={p} />)}

        {claims && claims.length > 0 && (
          <Panel title="Claim documents" subtitle="What was sent with each claim.">
            <ul className="divide-y divide-border">
              {claims.map((c) => <ClaimDocuments key={c.claimId} claim={c} />)}
            </ul>
          </Panel>
        )}
      </div>
    </>
  );
}

function PolicyDocuments({ policy }: { policy: CustomerPolicySummary }) {
  const n = policy.policyNumber;
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const hasAccount = policy.value != null && policy.productCategory !== 'UNIT_LINKED';
  const year = new Date().getFullYear();
  const period = { from: `${year}-01-01`, to: new Date().toISOString().slice(0, 10) };

  async function schedule() {
    setBusy(true);
    setError(null);
    try {
      saveBlob(await downloadPolicySchedule(n), `policy-schedule-${n}.pdf`);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title={policy.productName ?? categoryText(policy.productCategory)} subtitle={n}>
      <ul className="divide-y divide-border">
        <DocRow title="Policy schedule" note="What your policy covers, who it pays, and its premium.">
          <Button size="sm" variant="ghost" pending={busy} aria-label={`Download the policy schedule for ${n}`}
            onClick={() => void schedule()}>
            <Download />
            PDF
          </Button>
        </DocRow>
        {policy.premium != null && policy.premiumFrequency !== 'SINGLE' && (
          <DocRow title="Premium schedule" note="Every premium, what was paid and when.">
            <DownloadButtons what={`premium schedule for ${n}`}
              onDownload={async (format) => saveBlob(await downloadPaymentSchedule(n, format), documentFileName('payment-schedule', n, format))} />
          </DocRow>
        )}
        {hasAccount && (
          <DocRow title={`Savings statement ${year}`} note="Money in, money out and your balance this year.">
            <DownloadButtons what={`savings statement for ${n}`}
              onDownload={async (format) => saveBlob(await downloadSavingsStatement(n, format, period.from, period.to),
                documentFileName('savings-statement', n, format, period))} />
          </DocRow>
        )}
        <Receipts policyNumber={n} />
      </ul>
      {error && <div className="px-4 pb-3"><InlineError error={error} /></div>}
    </Panel>
  );
}

function DocRow({ title, note, children }: { title: string; note: string; children: React.ReactNode }) {
  return (
    <li className="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
      <div className="min-w-0">
        <p className="text-sm font-medium">{title}</p>
        <p className="text-xs text-muted-foreground">{note}</p>
      </div>
      {children}
    </li>
  );
}

/** Premium receipts, loaded when opened -- most visits are for the schedule. */
function Receipts({ policyNumber }: { policyNumber: string }) {
  const [open, setOpen] = useState(false);
  const [receipts, setReceipts] = useState<ReceiptLine[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    if (!open || receipts !== null) return undefined;
    let live = true;
    listReceipts(policyNumber).then((r) => { if (live) setReceipts(r); }, (e: unknown) => { if (live) setError(toApiError(e)); });
    return () => { live = false; };
  }, [open, receipts, policyNumber]);

  return (
    <li>
      <button type="button" aria-expanded={open} onClick={() => setOpen((o) => !o)}
        className="flex w-full items-center gap-2 px-4 py-3 text-left hover:bg-hover">
        {open ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
        <span className="min-w-0">
          <span className="block text-sm font-medium">Premium receipts</span>
          <span className="block text-xs text-muted-foreground">A receipt for every premium you have paid.</span>
        </span>
      </button>
      {open && (
        <div className="border-t border-border px-4 py-2">
          {error ? <InlineError error={error} />
            : receipts === null ? <p className="py-1 text-xs text-muted-foreground">Loading…</p>
              : receipts.length === 0 ? <p className="py-1 text-xs text-muted-foreground">No premium received yet.</p>
                : (
                  <ul className="divide-y divide-border">
                    {receipts.map((r) => (
                      <li key={r.receiptId} className="flex flex-wrap items-center justify-between gap-2 py-2 text-sm">
                        <span>
                          {money(r.amount, r.currency)} <span className="text-xs text-muted-foreground">
                            received {formatDate(r.receivedOn)}{r.reference ? ` · ${r.reference}` : ''}</span>
                          {r.coversFrom && r.coversTo && (
                            <span className="block text-xs text-subtle-foreground">
                              Cover {formatDate(r.coversFrom)} to {formatDate(r.coversTo)}
                            </span>
                          )}
                        </span>
                        <Button size="sm" variant="ghost" aria-label={`Download the receipt of ${formatDate(r.receivedOn)}`}
                          onClick={async () => saveBlob(await downloadReceipt(policyNumber, r.receiptId), `receipt-${policyNumber}-${r.receivedOn}.pdf`)}>
                          <Download />
                          Receipt
                        </Button>
                      </li>
                    ))}
                  </ul>
                )}
        </div>
      )}
    </li>
  );
}

function ClaimDocuments({ claim }: { claim: ClaimView }) {
  const [open, setOpen] = useState(false);
  const [files, setFiles] = useState<ClaimEvidenceView[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    if (!open || files !== null || !claim.claimId) return undefined;
    let live = true;
    listClaimEvidence(claim.claimId).then((f) => { if (live) setFiles(f); }, (e: unknown) => { if (live) setError(toApiError(e)); });
    return () => { live = false; };
  }, [open, files, claim.claimId]);

  return (
    <li>
      <button type="button" aria-expanded={open} onClick={() => setOpen((o) => !o)}
        className="flex w-full items-center gap-2 px-4 py-3 text-left hover:bg-hover">
        {open ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
        <span className="text-sm">
          {(claim.claimType ?? '').toLowerCase().replaceAll('_', ' ')} claim · {claim.policyNumber}
          <span className="ml-1 text-xs text-muted-foreground">{claim.status ? claimStatusText(claim.status) : ''}</span>
        </span>
      </button>
      {open && (
        <div className="border-t border-border px-4 py-2">
          {error ? <InlineError error={error} />
            : files === null ? <p className="py-1 text-xs text-muted-foreground">Loading…</p>
              : files.length === 0 ? <p className="py-1 text-xs text-muted-foreground">No documents on this claim.</p>
                : (
                  <ul className="divide-y divide-border">
                    {files.map((f) => (
                      <li key={f.claimEvidenceId} className="flex flex-wrap items-center justify-between gap-2 py-2 text-sm">
                        <span>{f.description || 'Document'} <span className="text-xs text-muted-foreground">
                          · {formatInstant(f.uploadedAt)}</span></span>
                        <Button size="sm" variant="ghost" aria-label={`Download ${f.description || 'document'}`}
                          onClick={async () => saveBlob(await downloadClaimEvidence(claim.claimId as string, f.documentRef as string),
                            f.description || 'claim-document')}>
                          <Download />
                          Download
                        </Button>
                      </li>
                    ))}
                  </ul>
                )}
        </div>
      )}
    </li>
  );
}
