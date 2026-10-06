import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import type { ReinsuranceStatementStatus } from '@/api/types';
import { FormField } from '@/components/FormField';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Select } from '@/components/ui/input';
import { formatInstant } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useReinsuranceStatementsStore } from '@/store/reinsuranceStatementsStore';
import { STATEMENT_STATUS_LABEL, settlementSide } from './statementForm';

/**
 * Every treaty's quarterly statements (IFRS 17 I3d), newest first -- where a finance approver finds what is waiting
 * for them. A statement is prepared from its treaty's page.
 */
export function ReinsuranceStatementsPage() {
  const list = useReinsuranceStatementsStore((s) => s.list);
  const loadList = useReinsuranceStatementsStore((s) => s.loadList);
  const [status, setStatus] = useState<ReinsuranceStatementStatus | ''>('SUBMITTED');

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
      return <EmptyState title="No statements" description="A quarter's statement is prepared from its treaty's page." />;
    }
    return (
      <div className="overflow-x-auto rounded-lg border border-border bg-surface">
        <table className="w-full text-sm" aria-label="Reinsurance statements">
          <thead>
            <tr className="text-left text-xs text-muted-foreground">
              <th className="px-3 py-2 font-normal">Reinsurer</th>
              <th className="w-24 px-3 py-2 font-normal">Quarter</th>
              <th className="w-40 px-3 py-2 font-normal">Status</th>
              <th className="px-3 py-2 font-normal">Balance</th>
              <th className="w-44 px-3 py-2 font-normal">Prepared</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((s) => (
              <tr key={s.statementId} className="border-t border-border">
                <td className="px-3 py-2">
                  <Link className="font-medium underline-offset-2 hover:underline"
                    to={`../treaties/${s.treatyId}/statements/${s.statementId}`} relative="path">
                    {s.reinsurerName ?? s.treatyId}
                  </Link>
                </td>
                <td className="px-3 py-2 tabular-nums">{s.quarter}</td>
                <td className="px-3 py-2">{STATEMENT_STATUS_LABEL[s.status] ?? s.status}</td>
                <td className="px-3 py-2">{settlementSide(s)}</td>
                <td className="px-3 py-2 text-xs text-muted-foreground">{formatInstant(s.preparedAt)}</td>
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
        title="Reinsurance statements"
        description="Each treaty's quarter, settled into the reinsurer current account. One person prepares, a finance approver posts."
      />
      <div className="space-y-4 px-6 pb-6">
        <div className="w-56">
          <FormField label="Status">
            <Select value={status} onChange={(e) => setStatus(e.target.value as ReinsuranceStatementStatus | '')}>
              <option value="">All</option>
              {Object.entries(STATEMENT_STATUS_LABEL).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </Select>
          </FormField>
        </div>
        {renderBody()}
      </div>
    </>
  );
}
