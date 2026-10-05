import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { useAuth } from 'react-oidc-context';
import type { ElectionKey, PolicyElectionView } from '@/api/types';
import { readIdentity } from '@/auth/claims';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { decideElectionGates } from '@/gates/ledgerControlGates';
import { formatDate, formatInstant, todayIso } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useLedgerControlsStore } from '@/store/ledgerControlsStore';
import {
  ELECTION_KEYS,
  ELECTION_VALUES,
  approvalSchema,
  electionSchema,
  rejectionSchema,
  type ApprovalValues,
  type ElectionValues,
  type RejectionValues,
} from './registerForms';

const KEY_LABEL: Record<ElectionKey, string> = {
  MEASUREMENT_MODEL: 'Measurement model',
  INVESTMENT_COMPONENT_RULE: 'Investment component',
  MODEL_OVERRIDE_ALLOWED: 'Model override allowed',
  POLICY_LOANS: 'Policy loans',
  ACQUISITION_CASH_FLOWS: 'Acquisition cash flows',
  OCI_OPTION: 'OCI option',
  RIDERS: 'Riders',
  PREMIUM_BILLING: 'Premium billing',
  CONTRACT_RECOGNITION: 'Contract recognition',
  COHORT: 'Cohort',
};

const keyLabel = (key: string) => KEY_LABEL[key as ElectionKey] ?? key;

/**
 * The accounting policy register (IFRS 17 spec §3): the elections the ledger and the IFRS 17 engine apply,
 * effective-dated. A change is proposed from today or later and approved by a second person with the sign-off it
 * rests on; it becomes the register's next version, and every journal records the version in force.
 */
export function PolicyRegisterPage() {
  const auth = useAuth();
  const viewerSubject = readIdentity(auth.user?.access_token)?.subject ?? undefined;
  const elections = useLedgerControlsStore((s) => s.elections);
  const loadElections = useLedgerControlsStore((s) => s.loadElections);

  useEffect(() => {
    void loadElections();
  }, [loadElections]);

  function renderBody() {
    if (isInitialLoad(elections)) return <LoadingBlock />;
    if (elections.status === 'error' && elections.error && elections.data === null) {
      return <ErrorPanel error={elections.error} onRetry={() => void loadElections()} />;
    }
    const rows = elections.data ?? [];
    const today = todayIso();
    const inForce = rows.filter((e) => e.status === 'APPROVED' && e.effectiveFrom <= today);
    const scheduled = rows.filter((e) => e.status === 'APPROVED' && e.effectiveFrom > today);
    const proposed = rows.filter((e) => e.status === 'PROPOSED');
    const keys = [...new Set(inForce.map((e) => e.key))];
    return (
      <>
        <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Awaiting a decision">
          <p className="text-xs font-medium">Awaiting a decision</p>
          {proposed.length === 0 ? (
            <EmptyState title="Nothing awaiting a decision" description="A proposed change appears here until a second person decides it." />
          ) : (
            <div className="divide-y divide-border rounded-md border border-border" role="list" aria-label="Proposed elections">
              {proposed.map((e) => (
                <ProposedRow key={e.electionId} election={e} viewerSubject={viewerSubject} />
              ))}
            </div>
          )}
        </section>
        {scheduled.length > 0 && (
          <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Approved, not yet in force">
            <p className="text-xs font-medium">Approved, not yet in force</p>
            <ElectionTable label="Scheduled elections" rows={scheduled} showKey />
          </section>
        )}
        <section className="space-y-3 rounded-lg border border-border bg-surface p-4" aria-label="In force today">
          <p className="text-xs font-medium">In force today</p>
          {keys.map((key) => (
            <ElectionTable key={key} label={keyLabel(key)} rows={inForce.filter((e) => e.key === key)} />
          ))}
        </section>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Accounting policies"
        description="The IFRS 17 elections in force. A change applies from today or later, and a second person approves it."
      />
      <div className="space-y-4 px-6 pb-6">
        {renderBody()}
        <ProposeForm />
      </div>
    </>
  );
}

function ElectionTable({ label, rows, showKey = false }: { label: string; rows: PolicyElectionView[]; showKey?: boolean }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm" aria-label={label}>
        <caption className="pb-1 text-left text-xs font-medium text-muted-foreground">{label}</caption>
        <thead>
          <tr className="text-left text-xs text-muted-foreground">
            {showKey && <th className="w-48 py-1 pr-3 font-normal">Election</th>}
            <th className="w-32 py-1 pr-3 font-normal">Scope</th>
            <th className="w-56 py-1 pr-3 font-normal">Value</th>
            <th className="w-28 py-1 pr-3 font-normal">From</th>
            <th className="w-20 py-1 pr-3 font-normal">Version</th>
            <th className="py-1 font-normal">Sign-off</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((e) => (
            <tr key={e.electionId} className="border-t border-border align-top">
              {showKey && <td className="py-1 pr-3">{keyLabel(e.key)}</td>}
              <td className="py-1 pr-3 font-mono">{e.scope}</td>
              <td className="py-1 pr-3 font-mono" title={e.rationale ?? undefined}>
                {e.value}
              </td>
              <td className="py-1 pr-3">{formatDate(e.effectiveFrom)}</td>
              <td className="py-1 pr-3 tabular-nums">{e.registerVersion}</td>
              <td className="py-1 text-xs text-muted-foreground">{e.signOffRef}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ProposedRow({ election, viewerSubject }: { election: PolicyElectionView; viewerSubject: string | undefined }) {
  const acting = useLedgerControlsStore((s) => s.acting[`election.${election.electionId}`]);
  const gates = decideElectionGates(election, viewerSubject);
  const refused = gates.some((g) => !g.ok && g.hard);
  const busy = acting?.status === 'loading';
  const name = `${keyLabel(election.key)} ${election.scope} = ${election.value}`;

  return (
    <div role="listitem" aria-label={name} className="space-y-2 px-4 py-2.5">
      <div>
        <span className="text-sm font-medium">{name}</span> <StatusBadge kind="policyElection" value={election.status} />
        <p className="text-xs text-muted-foreground">
          From {formatDate(election.effectiveFrom)} · proposed by {election.proposedBy} {formatInstant(election.proposedAt)}
          {election.rationale ? ` · ${election.rationale}` : ''}
        </p>
      </div>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <GatePanel gates={gates} title="Before deciding" />
      <div className="flex flex-wrap items-start gap-4">
        <ApproveForm electionId={election.electionId} disabled={refused || busy} />
        <RejectForm electionId={election.electionId} disabled={refused || busy} />
      </div>
    </div>
  );
}

function ApproveForm({ electionId, disabled }: { electionId: string; disabled: boolean }) {
  const approve = useLedgerControlsStore((s) => s.approve);
  const form = useForm<ApprovalValues>({ resolver: zodResolver(approvalSchema), defaultValues: { signOffRef: '' } });
  return (
    <form
      className="flex items-end gap-2"
      aria-label="Approve election"
      onSubmit={form.handleSubmit((v) => void approve(electionId, v.signOffRef.trim()))}
    >
      <FormField label="Sign-off reference" error={form.formState.errors.signOffRef?.message}>
        <Input inputSize="sm" placeholder="Audit committee minute 14/2026" disabled={disabled} {...form.register('signOffRef')} />
      </FormField>
      <Button type="submit" size="sm" disabled={disabled}>
        Approve
      </Button>
    </form>
  );
}

function RejectForm({ electionId, disabled }: { electionId: string; disabled: boolean }) {
  const reject = useLedgerControlsStore((s) => s.reject);
  const form = useForm<RejectionValues>({ resolver: zodResolver(rejectionSchema), defaultValues: { reason: '' } });
  return (
    <form
      className="flex items-end gap-2"
      aria-label="Reject election"
      onSubmit={form.handleSubmit((v) => void reject(electionId, v.reason.trim()))}
    >
      <FormField label="Reason to reject" error={form.formState.errors.reason?.message}>
        <Input inputSize="sm" disabled={disabled} {...form.register('reason')} />
      </FormField>
      <Button type="submit" size="sm" variant="ghost" disabled={disabled}>
        Reject
      </Button>
    </form>
  );
}

function ProposeForm() {
  const propose = useLedgerControlsStore((s) => s.propose);
  const acting = useLedgerControlsStore((s) => s.acting['election.propose']);
  const [today] = useState(todayIso);
  const form = useForm<ElectionValues>({
    resolver: zodResolver(electionSchema(today)),
    defaultValues: { key: 'OCI_OPTION', scope: '*', value: '', effectiveFrom: '', rationale: '' },
  });
  const key = useWatch({ control: form.control, name: 'key' }) as ElectionKey;

  return (
    <form
      className="space-y-3 rounded-lg border border-border bg-surface p-4"
      aria-label="Propose a change"
      onSubmit={form.handleSubmit(async (v) => {
        const ok = await propose({
          key: v.key as ElectionKey,
          scope: v.scope === '' ? '*' : v.scope,
          value: v.value,
          effectiveFrom: v.effectiveFrom,
          rationale: v.rationale === '' ? null : v.rationale,
        });
        if (ok) form.reset();
      })}
    >
      <p className="text-xs font-medium">Propose a change</p>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <FormField label="Election" error={form.formState.errors.key?.message}>
          <Select
            {...form.register('key', {
              onChange: () => form.setValue('value', ''),
            })}
          >
            {ELECTION_KEYS.map((k) => (
              <option key={k} value={k}>
                {KEY_LABEL[k]}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField label="Scope" hint="A portfolio code (TERM, ULIP ...), a model for acquisition cash flows, or * for the company">
          <Input placeholder="*" {...form.register('scope')} />
        </FormField>
        <FormField label="Value" error={form.formState.errors.value?.message}>
          {key === 'MODEL_OVERRIDE_ALLOWED' ? (
            <Input placeholder="NONE, or GMM,PAA" {...form.register('value')} />
          ) : (
            <Select {...form.register('value')}>
              <option value="">Choose…</option>
              {(ELECTION_VALUES[key] ?? []).map((v) => (
                <option key={v} value={v}>
                  {v}
                </option>
              ))}
            </Select>
          )}
        </FormField>
        <FormField label="Effective from" error={form.formState.errors.effectiveFrom?.message}>
          <Controller
            control={form.control}
            name="effectiveFrom"
            render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
          />
        </FormField>
      </div>
      <FormField label="Rationale" error={form.formState.errors.rationale?.message}>
        <Input {...form.register('rationale')} />
      </FormField>
      <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
        Propose change
      </Button>
    </form>
  );
}
