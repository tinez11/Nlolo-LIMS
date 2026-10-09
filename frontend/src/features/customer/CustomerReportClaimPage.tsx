import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { attachClaimEvidence, registerClaim } from '@/api/claims';
import { getCustomerDashboard, getCustomerPolicy, getMe, type CustomerPolicySummary, type CustomerPolicyView } from '@/api/portal';
import { DatePicker } from '@/components/DatePicker';
import { Field } from '@/components/Field';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input, Select, Textarea } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate } from '@/lib/dates';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { EMPTY_CLAIM_FORM, missingForClaim, toClaimRequest, type CustomerClaimForm, type CustomerClaimType } from './customerClaimForm';
import { categoryText, claimTypeText, EVIDENCE_ACCEPT, roleText } from './customerText';

/** Policies a claim can still be made on: in force, or paid up. */
const CLAIMABLE = new Set(['ACTIVE', 'REINSTATED', 'PAID_UP', 'MATURED', 'EXPIRED']);
const TYPES: CustomerClaimType[] = ['DEATH', 'DISABILITY', 'CRITICAL_ILLNESS', 'MATURITY'];

/**
 * Report a claim (2026-10-08, the customer portal design step 4; PRD §17-18): which policy, what happened, the
 * documents to hand, then a review before it is sent. On funeral cover the question is who died, from the family the
 * plan covers. The claim is registered as the customer's own -- the server refuses any other claimant and any policy
 * they do not hold -- and the files go up as its evidence.
 */
export function CustomerReportClaimPage() {
  const navigate = useNavigate();
  const [policies, setPolicies] = useState<CustomerPolicySummary[] | null>(null);
  const [partyId, setPartyId] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);
  const [form, setForm] = useState<CustomerClaimForm>(EMPTY_CLAIM_FORM);
  const [policy, setPolicy] = useState<CustomerPolicyView | null>(null);
  const [files, setFiles] = useState<File[]>([]);
  const [reviewing, setReviewing] = useState(false);
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<ApiError | null>(null);
  const [attempt] = useState<MutationAttempt>(() => startMutation());

  useEffect(() => {
    let live = true;
    Promise.all([getMe(), getCustomerDashboard()]).then(
      ([me, d]) => {
        if (!live) return;
        setPartyId(me.partyId);
        setPolicies(d.policies.filter((p) => CLAIMABLE.has(p.status)));
        setLoadError(null);
      },
      (e: unknown) => { if (live) setLoadError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [reload]);

  // The chosen policy's detail: on funeral cover, its family is the "who died" list.
  useEffect(() => {
    if (!form.policyNumber) return;
    let live = true;
    getCustomerPolicy(form.policyNumber).then((p) => { if (live) setPolicy(p); }, () => { if (live) setPolicy(null); });
    return () => { live = false; };
  }, [form.policyNumber]);

  const set = (patch: Partial<CustomerClaimForm>) => setForm((f) => ({ ...f, ...patch }));
  const current = policy?.summary.policyNumber === form.policyNumber ? policy : null;
  const funeral = current?.summary.productCategory === 'FUNERAL';
  const lives = (current?.coveredLives ?? []).filter((l) => l.status === 'ACTIVE');
  const type: CustomerClaimType = funeral ? 'DEATH' : form.claimType;
  const effective = { ...form, claimType: type };
  const missing = missingForClaim(effective, funeral);

  async function submit() {
    if (!partyId) return;
    setSending(true);
    setSendError(null);
    try {
      const claim = await registerClaim(toClaimRequest(effective, partyId, funeral), attempt);
      // The claim exists now. A file that fails to go up is not a reason to stay here: the claim page lists what
      // arrived and has its own place to send more.
      for (const file of files) {
        await attachClaimEvidence(claim.claimId, file, file.name).catch(() => undefined);
      }
      navigate(`/customers/claims/${claim.claimId}`);
    } catch (e) {
      setSendError(toApiError(e));
      setSending(false);
    }
  }

  const breadcrumb = [{ label: 'My claims', to: '/customers/claims' }];
  if (loadError) {
    return (
      <>
        <PageHeader breadcrumb={breadcrumb} title="Report a claim" />
        <div className="px-4 pt-4 sm:px-6"><ErrorPanel error={loadError} onRetry={() => setReload((n) => n + 1)} /></div>
      </>
    );
  }
  if (!policies) return <LoadingBlock label="Loading your policies" />;
  if (policies.length === 0) {
    return (
      <>
        <PageHeader breadcrumb={breadcrumb} title="Report a claim" />
        <div className="px-4 pt-4 sm:px-6">
          <EmptyState title="No policy to claim on" description="A claim is made on a policy in force. Contact us if you think this is wrong." />
        </div>
      </>
    );
  }

  const chosenLife = lives.find((l) => l.coveredLifeId === form.coveredLifeId);
  const whatLabel = type === 'DEATH' ? 'Cause of death' : type === 'DISABILITY' ? 'What the disability is' : 'The illness diagnosed';

  if (reviewing) {
    return (
      <>
        <PageHeader breadcrumb={breadcrumb} title="Check your claim" description="Nothing is sent until you press Send claim." />
        <div className="space-y-4 px-4 pb-8 sm:px-6">
          <Panel title="Your claim">
            <dl className="px-4 pb-2">
              <Field label="Policy" value={`${current?.summary.productName ?? ''} ${form.policyNumber}`.trim()} />
              <Field label="Claim for" value={claimTypeText(type)} />
              {chosenLife && <Field label="Who died" value={`${chosenLife.name} (${roleText(chosenLife.role)})`} />}
              {funeral && <Field label="An accident" value={form.accidental ? 'Yes' : 'No'} />}
              <Field label={type === 'MATURITY' ? 'Maturity date' : 'Date'} value={formatDate(form.dateOfEvent)} />
              {type !== 'MATURITY' && <Field label={whatLabel} value={form.whatHappened} />}
              {type === 'DEATH' && <Field label="Where" value={form.placeOfDeath} />}
              {type === 'DEATH' && form.doctor && <Field label="Doctor or clinic" value={form.doctor} />}
              {type === 'DISABILITY' && <Field label="Permanent" value={form.permanent ? 'Yes' : 'No'} />}
              <Field label="Documents" value={files.length === 0 ? 'None — you can send them later' : files.map((f) => f.name).join(', ')} />
            </dl>
          </Panel>
          {sendError && <InlineError error={sendError} lead="Your claim was not sent" />}
          <div className="flex flex-wrap gap-2">
            <Button variant="primary" onClick={() => void submit()} pending={sending}>Send claim</Button>
            <Button variant="outline" onClick={() => setReviewing(false)} disabled={sending}>Change something</Button>
          </div>
        </div>
      </>
    );
  }

  return (
    <>
      <PageHeader breadcrumb={breadcrumb} title="Report a claim"
        description="Tell us what happened. We will ask for anything else we need." />
      <div className="space-y-4 px-4 pb-8 sm:px-6">
        <Panel title="Which policy">
          <div className="space-y-3 px-4 py-3">
            <FormField label="Policy">
              <Select value={form.policyNumber}
                onChange={(e) => set({ policyNumber: e.target.value, coveredLifeId: '' })}>
                <option value="">Choose a policy</option>
                {policies.map((p) => (
                  <option key={p.policyNumber} value={p.policyNumber}>
                    {(p.productName ?? categoryText(p.productCategory))} — {p.policyNumber}
                  </option>
                ))}
              </Select>
            </FormField>
            {form.policyNumber && !current && <LoadingBlock label="Loading the policy" />}
            {current && !funeral && (
              <FormField label="What are you claiming for?">
                <Select value={form.claimType} onChange={(e) => set({ claimType: e.target.value as CustomerClaimType })}>
                  {TYPES.map((t) => <option key={t} value={t}>{claimTypeText(t)}</option>)}
                </Select>
              </FormField>
            )}
            {funeral && (
              <FormField label="Who died?" hint={lives.length === 0 ? 'Nobody on this plan is covered today.' : undefined}>
                <Select value={form.coveredLifeId} onChange={(e) => set({ coveredLifeId: e.target.value })}>
                  <option value="">Choose the person</option>
                  {lives.map((l) => (
                    <option key={l.coveredLifeId} value={l.coveredLifeId}>{l.name} ({roleText(l.role)})</option>
                  ))}
                </Select>
              </FormField>
            )}
          </div>
        </Panel>

        {current && (
          <Panel title="What happened">
            <div className="space-y-3 px-4 py-3">
              <FormField label={type === 'MATURITY' ? 'Maturity date' : type === 'DEATH' ? 'Date of death'
                : type === 'DISABILITY' ? 'When it started' : 'Date diagnosed'}>
                <DatePicker value={form.dateOfEvent || null} onChange={(iso) => set({ dateOfEvent: iso ?? '' })}
                  disabled={{ after: new Date() }} />
              </FormField>
              {type !== 'MATURITY' && (
                <FormField label={whatLabel}>
                  <Textarea rows={2} value={form.whatHappened} onChange={(e) => set({ whatHappened: e.target.value })} />
                </FormField>
              )}
              {type === 'DEATH' && (
                <>
                  <FormField label="Where it happened">
                    <Input value={form.placeOfDeath} onChange={(e) => set({ placeOfDeath: e.target.value })}
                      placeholder="e.g. Muhimbili Hospital, or at home in Kinondoni" />
                  </FormField>
                  <FormField label="Doctor or clinic, if known">
                    <Input value={form.doctor} onChange={(e) => set({ doctor: e.target.value })} />
                  </FormField>
                </>
              )}
              {funeral && (
                <label className="flex items-center gap-2 text-sm">
                  <input type="checkbox" checked={form.accidental} onChange={(e) => set({ accidental: e.target.checked })} />
                  It was an accident
                </label>
              )}
              {type === 'DISABILITY' && (
                <label className="flex items-center gap-2 text-sm">
                  <input type="checkbox" checked={form.permanent} onChange={(e) => set({ permanent: e.target.checked })} />
                  The doctor says it is permanent
                </label>
              )}
            </div>
          </Panel>
        )}

        {current && (
          <Panel title="Documents" subtitle="A death certificate, burial permit, medical report or ID — whatever you have now.">
            <div className="space-y-2 px-4 py-3">
              <FormField label="Add files" hint="Photos or PDFs. You can send more later from the claim.">
                <Input type="file" multiple accept={EVIDENCE_ACCEPT}
                  onChange={(e) => setFiles(Array.from(e.target.files ?? []))} />
              </FormField>
            </div>
          </Panel>
        )}

        <div className="flex flex-wrap items-center gap-3">
          <Button variant="primary" disabled={missing.length > 0} onClick={() => setReviewing(true)}>Review</Button>
          <Button asChild variant="ghost"><Link to="/customers/claims">Cancel</Link></Button>
          {missing.length > 0 && <span className="text-xs text-muted-foreground">{missing[0]}</span>}
        </div>
      </div>
    </>
  );
}
