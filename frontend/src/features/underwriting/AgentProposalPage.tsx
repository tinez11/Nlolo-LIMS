import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router-dom';
import { getCase } from '@/api/underwriting';
import {
  amendApplicant,
  amendTerms,
  getCaseHistory,
  getCaseQuotation,
  resubmitProposal,
  RETURN_CATEGORY_LABELS,
  type CaseEvent,
  type CaseQuotation,
} from '@/api/proposalAmendments';
import type { AgentQuotation } from '@/api/agentQuotations';
import { PREMIUM_FREQUENCIES, PREMIUM_FREQUENCY_LABELS, type UnderwritingCaseView } from '@/api/types';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { ProductName } from '@/components/ProductName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input, Select, Textarea } from '@/components/ui/input';
import { IndividualFields } from '@/features/party/IndividualFields';
import {
  editIndividualForm,
  registerIndividualFormSchema,
  toApiRequest,
  type RegisterIndividualFormValues,
} from '@/features/party/registerIndividualForm';
import { toApiError, type ApiError } from '@/lib/apiError';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { selectParty, usePartyStore } from '@/store/partyStore';
import { useProductStore } from '@/store/productStore';
import { AcceptedQuotationPanel } from './AcceptedQuotationPanel';
import { ProposalHistoryPanel } from './ProposalHistoryPanel';
import { QuotationPreview } from './QuotationPreview';
import { useTermAccountTerms } from './useTermAccountTerms';
import { isAmount, isWholeMonths } from './useQuotation';
import { PERSON_CONFLICT_FIELDS, placeConflictOnField } from '@/features/party/conflictField';

/**
 * One of the agent's proposals (2026-10-20): what the underwriter asked, the plan and the client's details to correct,
 * and the resubmission -- with the full history underneath, the same record the underwriter reads.
 *
 * <p>The server decides what may be done: corrections and resubmission only while the proposal is returned or
 * postponed. A decline shows its reason and nothing to act on, because it is final.
 */
export function AgentProposalPage() {
  const { caseId = '' } = useParams();
  const [view, setView] = useState<UnderwritingCaseView | null>(null);
  const [history, setHistory] = useState<CaseEvent[]>([]);
  const [quotation, setQuotation] = useState<CaseQuotation | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [tick, setTick] = useState(0);
  const reload = () => setTick((t) => t + 1);

  useEffect(() => {
    let live = true;
    getCase(caseId).then((c) => { if (live) setView(c); }, (e: unknown) => { if (live) setError(toApiError(e)); });
    getCaseHistory(caseId).then((h) => { if (live) setHistory(h); }, () => undefined);
    getCaseQuotation(caseId).then((q) => { if (live) setQuotation(q); }, () => undefined);
    return () => { live = false; };
  }, [caseId, tick]);

  if (error && !view) {
    return (
      <>
        <PageHeader breadcrumb={[{ label: 'Proposals', to: '/agents/proposals' }]} title="Proposal" />
        <div className="px-6 pt-6"><ErrorPanel error={error} /></div>
      </>
    );
  }
  if (!view) return <LoadingBlock label="Loading the proposal" />;

  const postponed = view.status === 'DECIDED' && view.decisionOutcome === 'POSTPONED';
  const declined = view.status === 'DECIDED' && view.decisionOutcome === 'DECLINED';
  // 2026-10-21: an accepted or loaded case is now reachable from the Proposals list, not only from "Needs your
  // action" -- where every case was either returned, postponed, or declined, so nothing yet said "accepted" here.
  const accepted = view.status === 'DECIDED' && (view.decisionOutcome === 'ACCEPT' || view.decisionOutcome === 'LOADED');
  const amendable = view.status === 'RETURNED' || postponed;
  const lastReturn = [...history].reverse().find((e) => e.kind === 'RETURNED');

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Proposals', to: '/agents/proposals' }]}
        title={`Proposal ${view.proposalNumber ?? ''}`}
        actions={
          view.status === 'DECIDED' ? (
            <StatusBadge kind="underwritingDecision" value={view.decisionOutcome} />
          ) : (
            <StatusBadge kind="underwritingCase" value={view.status} />
          )
        }
      />
      <div className="max-w-2xl space-y-4 px-6 pb-8">
        <Panel emphasis title={view.status === 'RETURNED' ? 'What the underwriter asked' : 'Where it is'}>
          <div className="space-y-2 px-4 pb-4 text-sm">
            {view.status === 'RETURNED' && (
              <>
                {(lastReturn?.reasons ?? []).map((r, i) => (
                  <div key={i} className="text-xs">
                    <p className="font-medium">{RETURN_CATEGORY_LABELS[r.category]}</p>
                    <p><span className="text-muted-foreground">What is wrong: </span>{r.problem}</p>
                    <p><span className="text-muted-foreground">What to change: </span>{r.change}</p>
                  </div>
                ))}
                {lastReturn?.note && <p className="text-xs text-muted-foreground">“{lastReturn.note}”</p>}
                <p className="text-xs">Correct what is asked below, then resubmit. It goes back to the underwriter.</p>
              </>
            )}
            {postponed && (
              <p className="text-xs">
                Postponed: {view.decisionDeclineReason ?? '—'}. When the customer is ready, correct anything needed and
                resubmit.
              </p>
            )}
            {declined && (
              <p className="text-xs">
                Declined: {view.decisionDeclineReason ?? '—'}. This decision is final. To propose again, start a{' '}
                <Link className="underline" to="/agents/quotation">new quotation</Link>.
              </p>
            )}
            {accepted && (
              <p className="text-xs">
                {view.decisionOutcome === 'LOADED' ? 'Accepted, with a loading.' : 'Accepted.'} Nothing is needed from
                you; the policy is issued from here.
              </p>
            )}
            {!amendable && !declined && !accepted && (
              <p className="text-xs">With the underwriter. Nothing is needed from you.</p>
            )}
          </div>
        </Panel>

        {amendable && (
          <PlanForm view={view} versionId={quotation?.productVersionId ?? ''} onSaved={(v) => { setView(v); reload(); }} />
        )}
        {amendable && view.applicantPartyId && (
          <ClientForm caseId={caseId} partyId={view.applicantPartyId} onSaved={reload} />
        )}
        {amendable && <ResubmitForm caseId={caseId} onDone={(v) => { setView(v); reload(); }} />}

        {quotation && (
          <AcceptedQuotationPanel
            quotation={{ ...quotation, productId: view.productId ?? '', applicantPartyId: null, caseId,
              proposalNumber: view.proposalNumber ?? null } as unknown as AgentQuotation}
            productName={view.productId ? <ProductName productId={view.productId} /> : undefined}
          />
        )}
        <ProposalHistoryPanel events={history} />
      </div>
    </>
  );
}

/** The contract as the proposal asks for it, re-quoted live as it is corrected -- the server's price, never ours. */
function PlanForm({ view, versionId, onSaved }: {
  view: UnderwritingCaseView;
  /** The version the proposal was quoted on, where it came from a quotation; a deposit and a funeral plan price by it. */
  versionId: string;
  onSaved: (v: UnderwritingCaseView) => void;
}) {
  const [sum, setSum] = useState(view.sumAssured?.amount ?? '');
  const [term, setTerm] = useState(view.requestedTermMonths != null ? String(view.requestedTermMonths) : '');
  const [ppt, setPpt] = useState(view.premiumPayingTermMonths != null ? String(view.premiumPayingTermMonths) : '');
  const [frequency, setFrequency] = useState(view.premiumFrequency ?? '');
  const [from, setFrom] = useState(view.proposedCommencementDate ?? '');
  // A term account's rate (2026-10-21), corrected with the rest of the plan; carried unchanged on any other product.
  const [rate, setRate] = useState(view.proposedRatePercent != null ? String(view.proposedRatePercent) : '');
  const [target, setTarget] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const loadParty = usePartyStore((s) => s.loadParty);
  const party = usePartyStore(selectParty(view.applicantPartyId ?? ''));
  const products = useProductStore((s) => s.list);
  const loadProducts = useProductStore((s) => s.loadList);
  useEffect(() => {
    void loadProducts();
    if (view.applicantPartyId) void loadParty(view.applicantPartyId);
  }, [view.applicantPartyId, loadParty, loadProducts]);
  const product = (products.data ?? []).find((p) => p.productId === view.productId);
  const termAccountTerms = useTermAccountTerms(view.productId, versionId, product?.portfolioCode);

  // The calculator's own checks, beside each field: a malformed number is not sent to come back as a 400.
  const sumError = sum && !isAmount(sum) ? 'Digits only, e.g. 1500000 — no commas, spaces or currency' : undefined;
  const termError = term && !isWholeMonths(term) ? 'A whole number of months, e.g. 120' : undefined;
  const pptError = ppt && !isWholeMonths(ppt)
    ? 'A whole number of months, e.g. 120'
    : ppt && term && isWholeMonths(term) && Number(ppt) > Number(term)
      ? 'Premiums cannot be paid for longer than the term'
      : undefined;
  const valid = isAmount(sum) && !termError && !pptError;

  async function save() {
    setBusy(true);
    setError(null);
    try {
      onSaved(await amendTerms(view.caseId ?? '', {
        sumAssured: Number(sum),
        requestedTermMonths: term ? Number(term) : null,
        premiumPayingTermMonths: ppt ? Number(ppt) : null,
        premiumFrequency: (frequency || null) as 'MONTHLY' | 'QUARTERLY' | 'ANNUALLY' | 'SINGLE' | null,
        proposedCommencementDate: from || null,
        proposedRatePercent: rate && !Number.isNaN(Number(rate)) ? Number(rate) : null,
      }));
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title="The plan" subtitle="Correct the cover, term or payments if the underwriter asked.">
      <div className="space-y-4 px-4 pb-4">
        <div className="grid grid-cols-2 gap-3">
          <FormField label={termAccountTerms ? 'Contribution per payment' : 'Sum assured or amount'} error={sumError}>
            <Input value={sum} onChange={(e) => setSum(e.target.value)} />
          </FormField>
          {termAccountTerms && (
            <>
              <FormField
                label="Rate (% a year)"
                hint={`This product offers ${termAccountTerms.minRatePercent}% to ${termAccountTerms.maxRatePercent}%, fixed for the term.`}
              >
                <Input inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} />
              </FormField>
              <FormField label="Wanted at maturity (optional)">
                <Input inputMode="decimal" placeholder="5000000" value={target} onChange={(e) => setTarget(e.target.value)} />
              </FormField>
            </>
          )}
          <FormField label="Premium frequency">
            <Select value={frequency} onChange={(e) => setFrequency(e.target.value as typeof frequency)}>
              <option value="">Not stated</option>
              {PREMIUM_FREQUENCIES.map((f) => (
                <option key={f} value={f}>{PREMIUM_FREQUENCY_LABELS[f]}</option>
              ))}
            </Select>
          </FormField>
          <FormField label="Term (months)" error={termError}>
            <Input value={term} onChange={(e) => setTerm(e.target.value)} />
          </FormField>
          <FormField label="Premium-paying term (months)" error={pptError}>
            <Input placeholder="Same as the term" value={ppt} onChange={(e) => setPpt(e.target.value)} />
          </FormField>
          <FormField label="Cover from">
            <DatePicker value={from || null} onChange={(iso) => setFrom(iso ?? '')} />
          </FormField>
        </div>
        <QuotationPreview
          inputs={{
            productId: view.productId ?? '',
            sumAssuredAmount: sum,
            sumAssuredCurrency: view.sumAssured?.currencyCode ?? 'TZS',
            frequency: frequency as never,
            policyTermMonths: term,
            dateOfBirth: party.data?.dateOfBirth,
            sex: party.data?.sex,
            smokerStatus: party.data?.smokerStatus,
            occupationClass: party.data?.occupationClass,
          }}
          premiumPayingTermMonths={ppt}
          commencementDate={from}
          portfolioCode={product?.portfolioCode}
          productVersionId={versionId}
          termAccount={
            termAccountTerms
              ? { ratePercent: rate, targetAmount: target, onUseContribution: (amount) => { setSum(amount); setTarget(''); } }
              : undefined
          }
        />
        {error && <InlineError error={error} lead="The plan could not be saved:" />}
        <Button type="button" variant="outline" pending={busy} disabled={!valid} onClick={() => void save()}>
          Save the plan
        </Button>
      </div>
    </Panel>
  );
}

/** The client's own record -- registration's fields -- corrected where the underwriter asked (user, 2026-10-20). */
function ClientForm({ caseId, partyId, onSaved }: { caseId: string; partyId: string; onSaved: () => void }) {
  const loadParty = usePartyStore((s) => s.loadParty);
  const party = usePartyStore(selectParty(partyId));
  useEffect(() => {
    void loadParty(partyId);
  }, [partyId, loadParty]);

  // A failed read is said, with a retry -- it used to wait on "Loading" for ever.
  if (party.status === 'error' && !party.data) {
    return (
      <Panel title="The client">
        <div className="px-4 pb-4">
          <ErrorPanel error={party.error ?? toApiError(null)} onRetry={() => void loadParty(partyId)} />
        </div>
      </Panel>
    );
  }
  if (!party.data) return <LoadingBlock label="Loading the client" />;
  return <ClientFormLoaded key={partyId} caseId={caseId} initial={editIndividualForm(party.data)} onSaved={() => {
    void loadParty(partyId);
    onSaved();
  }} />;
}

function ClientFormLoaded({ caseId, initial, onSaved }: {
  caseId: string;
  initial: RegisterIndividualFormValues;
  onSaved: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [saved, setSaved] = useState(false);
  const { register, control, handleSubmit, setError: setFieldError, formState: { errors } } =
    useForm<RegisterIndividualFormValues>({
      ...VALIDATE_ON_TOUCH,
      resolver: zodResolver(registerIndividualFormSchema),
      defaultValues: initial,
    });

  async function save(values: RegisterIndividualFormValues) {
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      await amendApplicant(caseId, toApiRequest(values));
      setSaved(true);
      onSaved();
    } catch (e) {
      // An ID or client reference already on another client goes under that field; the rest below the form.
      if (!placeConflictOnField(toApiError(e), setFieldError, PERSON_CONFLICT_FIELDS)) setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel
      title="The client"
      subtitle="Correct the client's details if the underwriter asked. A changed name or date of birth sends their ID back for verification."
    >
      <form className="space-y-4 px-4 pb-4" onSubmit={(e) => void handleSubmit(save)(e)}>
        <IndividualFields register={register} control={control} errors={errors} />
        {error && <InlineError error={error} />}
        {saved && <p role="status" className="text-xs text-muted-foreground">The client's details are saved.</p>}
        <Button type="submit" variant="outline" pending={busy}>Save the client's details</Button>
      </form>
    </Panel>
  );
}

/** Back to the underwriter: the same proposal, its next round. */
function ResubmitForm({ caseId, onDone }: { caseId: string; onDone: (v: UnderwritingCaseView) => void }) {
  const { control, handleSubmit } = useForm<{ note: string }>({ ...VALIDATE_ON_TOUCH, defaultValues: { note: '' } });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function resubmit({ note }: { note: string }) {
    setBusy(true);
    setError(null);
    try {
      onDone(await resubmitProposal(caseId, note.trim() || null));
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title="Resubmit" subtitle="When everything asked is corrected, send it back to the underwriter.">
      <form className="space-y-4 px-4 pb-4" onSubmit={(e) => void handleSubmit(resubmit)(e)}>
        <FormField label="Note to the underwriter (optional)">
          <Controller control={control} name="note" render={({ field }) => <Textarea className="min-h-12" {...field} />} />
        </FormField>
        {error && <InlineError error={error} />}
        <Button type="submit" variant="primary" pending={busy}>Resubmit to underwriting</Button>
      </form>
    </Panel>
  );
}
