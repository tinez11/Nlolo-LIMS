import { useEffect, useMemo, useState } from 'react';
import type { PostingRule } from '@/api/types';
import { FormField } from '@/components/FormField';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Input } from '@/components/ui/input';
import { formatDate } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useLedgerControlsStore } from '@/store/ledgerControlsStore';
import { modelsLabel, whenLabel } from './postingQueueForms';

/**
 * The posting rules in force (IFRS 17 I3a), read-only: every journal an event posts comes from these, and the rules
 * file is the source of truth until a database editor with maker-checker exists. Grouped by event.
 */
export function PostingRulesPage() {
  const rules = useLedgerControlsStore((s) => s.rules);
  const loadRules = useLedgerControlsStore((s) => s.loadRules);
  const [filter, setFilter] = useState('');

  useEffect(() => {
    void loadRules();
  }, [loadRules]);

  const byEvent = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    const groups = new Map<string, PostingRule[]>();
    for (const rule of rules.data?.rules ?? []) {
      const haystack = `${rule.id} ${rule.event} ${rule.description ?? ''} ${rule.lines.map((l) => l.account).join(' ')}`;
      if (needle !== '' && !haystack.toLowerCase().includes(needle)) continue;
      groups.set(rule.event, [...(groups.get(rule.event) ?? []), rule]);
    }
    return groups;
  }, [rules.data, filter]);

  function renderBody() {
    if (isInitialLoad(rules)) return <LoadingBlock />;
    if (rules.status === 'error' && rules.error && rules.data === null) {
      return <ErrorPanel error={rules.error} onRetry={() => void loadRules()} />;
    }
    if (byEvent.size === 0) {
      return <EmptyState title="No rule matches" description="Search by entry, event, account or description." />;
    }
    return [...byEvent.entries()].map(([event, eventRules]) => (
      <section key={event} className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label={event}>
        <p className="font-mono text-xs font-medium">{event}</p>
        {eventRules.map((rule) => (
          <RuleTable key={rule.id} rule={rule} />
        ))}
      </section>
    ));
  }

  return (
    <>
      <PageHeader
        title="Posting rules"
        description={
          rules.data
            ? `${rules.data.versionLabel} — every journal records the version it was posted under. Read-only: the rules file is the source of truth.`
            : 'The rules every event posts by. Read-only: the rules file is the source of truth.'
        }
      />
      <div className="space-y-4 px-6 pb-6">
        <div className="max-w-sm">
          <FormField label="Search rules">
            <Input inputSize="sm" value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="A-06, 2141, refund…" />
          </FormField>
        </div>
        {renderBody()}
      </div>
    </>
  );
}

function RuleTable({ rule }: { rule: PostingRule }) {
  const when = whenLabel(rule.when);
  const dates = rule.effectiveTo
    ? `${formatDate(rule.effectiveFrom)} to ${formatDate(rule.effectiveTo)}`
    : `from ${formatDate(rule.effectiveFrom)}`;
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm" aria-label={`Rule ${rule.id}`}>
        <caption className="pb-1 text-left text-xs text-muted-foreground">
          <span className="font-mono font-medium text-foreground">{rule.id}</span> · {modelsLabel(rule.models)}
          {when ? ` · when ${when}` : ''} · {dates}
          {rule.description ? ` — ${rule.description}` : ''}
        </caption>
        <thead>
          <tr className="text-left text-xs text-muted-foreground">
            <th className="w-12 py-1 pr-3 font-normal">Side</th>
            <th className="py-1 pr-3 font-normal">Account</th>
            <th className="w-40 py-1 pr-3 font-normal">Amount</th>
            <th className="w-28 py-1 font-normal">Movement</th>
          </tr>
        </thead>
        <tbody>
          {rule.lines.map((line, i) => (
            <tr key={i} className="border-t border-border">
              <td className="py-1 pr-3 font-mono">{line.side === 'DR' ? 'Dr' : 'Cr'}</td>
              <td className="py-1 pr-3">
                <span className="font-mono">{line.account}</span>
                {line.accountName ? <span className="text-muted-foreground"> {line.accountName}</span> : null}
              </td>
              <td className="py-1 pr-3 font-mono">{line.amount}</td>
              <td className="py-1 font-mono">{line.movement ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
