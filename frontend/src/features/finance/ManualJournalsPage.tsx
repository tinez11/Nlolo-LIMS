import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Select } from '@/components/ui/input';
import { FormField } from '@/components/FormField';
import { formatInstant } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useManualJournalsStore } from '@/store/manualJournalsStore';

export const STATUS_LABEL: Record<string, string> = {
  DRAFT: 'Draft',
  SUBMITTED: 'Awaiting approval',
  APPROVED: 'Posted',
  REJECTED: 'Rejected',
};

const money = (n: number) => n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/**
 * Manual journals (IFRS 17 I4): the entries the policy system cannot make, prepared by one person and posted only
 * when a second, holding the finance-approver role, approves them.
 */
export function ManualJournalsPage() {
  const list = useManualJournalsStore((s) => s.list);
  const loadList = useManualJournalsStore((s) => s.loadList);
  const [status, setStatus] = useState('');

  useEffect(() => {
    void loadList(status || undefined);
  }, [loadList, status]);

  function renderBody() {
    if (isInitialLoad(list)) return <LoadingBlock />;
    if (list.status === 'error' && list.error && list.data === null) {
      return <ErrorPanel error={list.error} onRetry={() => void loadList(status || undefined)} />;
    }
    const rows = list.data ?? [];
    if (rows.length === 0) {
      return <EmptyState title="No manual journals" description="Start one from a template or a blank journal." />;
    }
    return (
      <div className="overflow-x-auto rounded-lg border border-border bg-surface">
        <table className="w-full text-sm" aria-label="Manual journals">
          <thead>
            <tr className="text-left text-xs text-muted-foreground">
              <th className="px-3 py-2 font-normal">Journal</th>
              <th className="w-24 px-3 py-2 font-normal">Period</th>
              <th className="w-40 px-3 py-2 font-normal">Status</th>
              <th className="w-36 px-3 py-2 text-right font-normal">Amount</th>
              <th className="w-44 px-3 py-2 font-normal">Prepared</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((j) => (
              <tr key={j.id} className="border-t border-border">
                <td className="px-3 py-2">
                  <Link className="font-medium underline-offset-2 hover:underline" to={j.id}>
                    {j.title}
                  </Link>
                  {j.reversesJournalId && <span className="ml-2 text-xs text-muted-foreground">reversal</span>}
                </td>
                <td className="px-3 py-2 tabular-nums">{j.period}</td>
                <td className="px-3 py-2">{STATUS_LABEL[j.status] ?? j.status}</td>
                <td className="px-3 py-2 text-right tabular-nums">{money(j.totalDebit)}</td>
                <td className="px-3 py-2 text-xs text-muted-foreground">{formatInstant(j.preparedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  return (
    <>
      <PageHeader
        title="Manual journals"
        description="Journals the policy system cannot make. One person prepares, a finance approver posts."
      />
      <div className="space-y-4 px-6 pb-6">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div className="w-56">
            <FormField label="Status">
              <Select value={status} onChange={(e) => setStatus(e.target.value)}>
                <option value="">All</option>
                {Object.entries(STATUS_LABEL).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </Select>
            </FormField>
          </div>
          <div className="flex gap-2">
            <Button asChild variant="outline" size="sm">
              <Link to="../journal-templates" relative="path">
                Templates
              </Link>
            </Button>
            <Button asChild variant="primary" size="sm">
              <Link to="new">New journal</Link>
            </Button>
          </div>
        </div>
        {renderBody()}
      </div>
    </>
  );
}
