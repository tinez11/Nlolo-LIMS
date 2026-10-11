import { useState, type FormEvent } from 'react';
import type { ReportDefinition } from '@/api/reporting';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { validateFilters, type ReportFilterState } from './reportParams';

const GROUP_LABELS: Record<string, string> = {
  PRODUCT: 'Product',
  PRODUCT_CATEGORY: 'Product category',
  AGENT: 'Agent',
  CHANNEL: 'Sales channel',
  BRANCH: 'Branch',
};

const SELECT = 'h-8 rounded-md border border-border-strong bg-surface px-2 text-xs';

/**
 * The filters a report takes, and nothing else: which dates show is the report's dateFilterKind. Keyed by
 * report at the call site, so moving to another report starts from that report's own defaults.
 */
export function ReportFilterBar({
  definition,
  initial,
  onRun,
}: {
  definition: ReportDefinition;
  initial: ReportFilterState;
  onRun: (state: ReportFilterState) => void;
}) {
  const [state, setState] = useState(initial);
  const [error, setError] = useState<string | null>(null);
  const kind = definition.dateFilterKind;
  const set = (field: keyof ReportFilterState) => (e: { target: { value: string } }) =>
    setState((s) => ({ ...s, [field]: e.target.value }));

  function submit(e: FormEvent) {
    e.preventDefault();
    const problem = validateFilters(definition, state);
    setError(problem);
    if (!problem) onRun(state);
  }

  const field = (id: keyof ReportFilterState, label: string, type: 'date' | 'month') => (
    <label className="flex flex-col gap-1 text-xs" htmlFor={`report-${id}`}>
      {label}
      <Input id={`report-${id}`} type={type} value={state[id]} onChange={set(id)} inputSize="sm" />
    </label>
  );

  return (
    <form onSubmit={submit} className="space-y-2 rounded-lg border border-border bg-surface p-3">
      <div className="flex flex-wrap items-end gap-3">
        {(kind === 'AS_OF' || kind === 'AS_OF_AND_PERIOD') && field('asOf', 'As of', 'date')}
        {kind === 'AS_OF' && definition.comparable && field('compareAsOf', 'Compare with', 'date')}
        {(kind === 'PERIOD' || kind === 'AS_OF_AND_PERIOD') && field('from', 'From', 'date')}
        {(kind === 'PERIOD' || kind === 'AS_OF_AND_PERIOD') && field('to', 'To', 'date')}
        {kind === 'COHORT' && field('cohortFrom', 'First activation month', 'month')}
        {kind === 'COHORT' && field('cohortTo', 'Last activation month', 'month')}
        {definition.groupByOptions.length > 0 && (
          <label className="flex flex-col gap-1 text-xs" htmlFor="report-groupBy">
            Group by
            <select id="report-groupBy" value={state.groupBy} onChange={set('groupBy')} className={SELECT}>
              {/* The first option is each report's default, so it sends nothing and keeps the URL clean. */}
              {definition.groupByOptions.map((g, i) => (
                <option key={g} value={i === 0 ? '' : g}>
                  {GROUP_LABELS[g] ?? g}
                </option>
              ))}
            </select>
          </label>
        )}
        <label className="flex flex-col gap-1 text-xs" htmlFor="report-clientType">
          Client type
          <select id="report-clientType" value={state.clientType} onChange={set('clientType')} className={SELECT}>
            <option value="">All clients</option>
            <option value="INDIVIDUAL">Individuals</option>
            <option value="CORPORATE">Corporates</option>
            <option value="GROUP">Groups</option>
          </select>
        </label>
        <Button type="submit" size="sm" variant="primary">
          Run report
        </Button>
      </div>
      {error && (
        <p role="alert" className="rounded-md bg-status-danger-bg px-3 py-2 text-sm text-status-danger-fg">
          {error}
        </p>
      )}
    </form>
  );
}
