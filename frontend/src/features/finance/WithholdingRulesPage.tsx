import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { useAuth } from 'react-oidc-context';
import type { WithholdingRuleView } from '@/api/types';
import { readIdentity } from '@/auth/claims';
import { DatePicker } from '@/components/DatePicker';
import { FormField, RequiredNote } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { approveRuleGates } from '@/gates/withholdingGates';
import { formatDate, todayIso } from '@/lib/dates';
import { startMutation } from '@/lib/idempotency';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useAnnuityStore } from '@/store/annuityStore';
import { endRuleSchema, withholdingRuleSchema, type EndRuleValues, type WithholdingRuleValues } from './withholdingRuleForm';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { StaffName } from '@/components/StaffName';

const KIND_LABEL: Record<string, string> = {
  ANNUITY: 'Annuity income',
  SURVIVAL: 'Survival benefits',
  MATURITY: 'Maturities',
  INCOME: 'Income payouts',
  RETURN_OF_PREMIUM: 'Premium returns',
  COMMUTATION: 'Pension lump sums',
};

/**
 * Tax withheld from payouts (product step 5). The rule is the law's, so it is data finance enters --
 * which payouts, what rate, from when, under which reference -- and a second person approves it.
 * Nothing is withheld from any payout until a rule is approved.
 */
export function WithholdingRulesPage() {
  const auth = useAuth();
  const viewerSubject = readIdentity(auth.user?.access_token)?.subject ?? undefined;
  const rules = useAnnuityStore((s) => s.rules);
  const loadRules = useAnnuityStore((s) => s.loadRules);

  useEffect(() => {
    void loadRules();
  }, [loadRules]);

  function renderList() {
    if (isInitialLoad(rules)) return <LoadingBlock />;
    if (rules.status === 'error' && rules.error && rules.data === null) {
      return <ErrorPanel error={rules.error} onRetry={() => void loadRules()} />;
    }
    const rows = rules.data ?? [];
    if (rows.length === 0) {
      return (
        <EmptyState
          title="No withholding rule"
          description="Nothing is withheld from any payout until a rule is approved."
        />
      );
    }
    return (
      <div className="divide-y divide-border rounded-md border border-border" role="list" aria-label="Withholding rules">
        {rows.map((rule) => (
          <RuleRow key={rule.ruleId} rule={rule} viewerSubject={viewerSubject} />
        ))}
      </div>
    );
  }

  return (
    <>
      <PageHeader
        title="Withholding rules"
        description="A second person approves each rule."
      />
      <div className="space-y-4 px-6 pb-6">
        <div className="rounded-xl border border-border bg-surface shadow-card p-4">{renderList()}</div>
        <ProposeForm />
      </div>
    </>
  );
}

function RuleRow({ rule, viewerSubject }: { rule: WithholdingRuleView; viewerSubject: string | undefined }) {
  const approve = useAnnuityStore((s) => s.approveRule);
  const withdraw = useAnnuityStore((s) => s.withdrawRule);
  const acting = useAnnuityStore((s) => s.acting[rule.ruleId]);
  const gates = approveRuleGates(rule, viewerSubject);
  const refused = gates.some((g) => !g.ok && g.hard);
  const ended = rule.status === 'APPROVED' && !!rule.effectiveTo && rule.effectiveTo < todayIso();

  return (
    <div role="listitem" className="space-y-2 px-4 py-2.5">
      <div>
        <span className="text-sm font-medium">
          {rule.ratePercent}% from {rule.payoutKinds.map((k) => KIND_LABEL[k] ?? k).join(', ')}
        </span>{' '}
        {/* A rule past its last day withholds nothing more: it reads Ended, not Approved (2026-11-10). */}
        {ended ? (
          <span className="rounded-full bg-status-neutral-bg px-2 py-0.5 text-xs text-status-neutral-fg">
            Ended {formatDate(rule.effectiveTo)}
          </span>
        ) : (
          <StatusBadge kind="withholdingRule" value={rule.status} />
        )}
        <p className="text-xs text-muted-foreground">
          From {formatDate(rule.effectiveFrom)}
          {rule.effectiveTo ? ` to ${formatDate(rule.effectiveTo)}` : ''} · {rule.legalReference} · proposed by <StaffName subject={rule.proposedBy} />
          {rule.approvedBy && <> · approved by <StaffName subject={rule.approvedBy} /></>}
          {rule.endedBy && <> · ended by <StaffName subject={rule.endedBy} /></>}
        </p>
      </div>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      {rule.status === 'APPROVED' && !ended && <EndRuleForm rule={rule} busy={acting?.status === 'loading'} />}
      {rule.status === 'PROPOSED' && (
        <>
          <GatePanel gates={gates} title="Before approving" />
          <div className="flex gap-2">
            <Button size="sm" disabled={refused || acting?.status === 'loading'} onClick={() => void approve(rule.ruleId)}>
              Approve rule
            </Button>
            <Button size="sm" variant="ghost" disabled={acting?.status === 'loading'} onClick={() => void withdraw(rule.ruleId)}>
              Withdraw
            </Button>
          </div>
        </>
      )}
    </div>
  );
}

/**
 * Ends an approved rule on its last day. One finance officer, alone (the user's decision); never in
 * the past, and only ever earlier -- a later period is a new rule, which takes two people.
 */
function EndRuleForm({ rule, busy }: { rule: WithholdingRuleView; busy: boolean }) {
  const endRule = useAnnuityStore((s) => s.endRule);
  const form = useForm<EndRuleValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(endRuleSchema(rule, todayIso())),
    defaultValues: { effectiveTo: '' },
  });
  return (
    <form
      className="flex flex-wrap items-end gap-2"
      aria-label={`End rule ${rule.ratePercent}% from ${formatDate(rule.effectiveFrom)}`}
      onSubmit={form.handleSubmit((v) => void endRule(rule.ruleId, v.effectiveTo))}
    >
      <FormField required label="Last day" error={form.formState.errors.effectiveTo?.message}>
        <Controller
          control={form.control}
          name="effectiveTo"
          render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
        />
      </FormField>
      <Button type="submit" size="sm" variant="ghost" disabled={busy}>
        End rule
      </Button>
    </form>
  );
}

function ProposeForm() {
  const propose = useAnnuityStore((s) => s.proposeRule);
  const acting = useAnnuityStore((s) => s.acting['rule.propose']);
  const form = useForm<WithholdingRuleValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(withholdingRuleSchema),
    defaultValues: { payoutKinds: ['ANNUITY'], ratePercent: '', effectiveFrom: '', effectiveTo: '', legalReference: '' },
  });

  return (
    <form
      className="space-y-3 rounded-xl border border-border bg-surface shadow-card p-4"
      onSubmit={form.handleSubmit((v) =>
        void propose(
          {
            payoutKinds: v.payoutKinds,
            ratePercent: Number(v.ratePercent),
            effectiveFrom: v.effectiveFrom,
            effectiveTo: v.effectiveTo === '' ? null : v.effectiveTo,
            legalReference: v.legalReference,
          },
          startMutation(),
        ).then(() => {
          if (useAnnuityStore.getState().acting['rule.propose']?.status === 'success') form.reset();
        }),
      )}
    >
      <RequiredNote />
      <p className="text-xs font-medium">Propose a rule</p>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      {/* Every scheduled benefit (2026-11-10, the user: "it only shows annuity, there must be more"): the server takes any
          kind, and which ones the law taxes is finance's to say. */}
      <FormField required label="Payout kinds" error={form.formState.errors.payoutKinds?.message}>
        <div className="grid grid-cols-1 gap-1 sm:grid-cols-2">
          {Object.entries(KIND_LABEL).map(([kind, label]) => (
            <CheckboxField key={kind} label={label} value={kind} {...form.register('payoutKinds')} />
          ))}
        </div>
      </FormField>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <FormField required label="Rate (%)" error={form.formState.errors.ratePercent?.message}>
          <Input inputMode="decimal" placeholder="10" {...form.register('ratePercent')} />
        </FormField>
        <FormField required label="Legal reference" error={form.formState.errors.legalReference?.message}>
          <Input placeholder="Income Tax Act, s.82" {...form.register('legalReference')} />
        </FormField>
        <FormField required label="Effective from" error={form.formState.errors.effectiveFrom?.message}>
          <Controller
            control={form.control}
            name="effectiveFrom"
            render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
          />
        </FormField>
        <FormField label="Effective to" error={form.formState.errors.effectiveTo?.message}>
          <Controller
            control={form.control}
            name="effectiveTo"
            render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
          />
        </FormField>
      </div>
      <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
        Propose rule
      </Button>
    </form>
  );
}
