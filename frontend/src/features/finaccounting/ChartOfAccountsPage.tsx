import { useEffect } from 'react';
import type { ChartOfAccountView } from '@/api/types';
import { PageHeader } from '@/components/AppShell';
import { DataTable, type Column } from '@/components/DataTable';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useFinaccountingStore } from '@/store/finaccountingStore';

/**
 * `GET /chart-of-accounts` -- a bare array, no pager. Every row is
 * PLACEHOLDER, seeded by `ChartOfAccountSeeder`, never authored through this
 * or any API -- there is no "New account" action anywhere on this page,
 * because there is no such endpoint.
 */
export function ChartOfAccountsPage() {
  const accounts = useFinaccountingStore((s) => s.chartOfAccounts);
  const loadChartOfAccounts = useFinaccountingStore((s) => s.loadChartOfAccounts);

  useEffect(() => {
    void loadChartOfAccounts();
  }, [loadChartOfAccounts]);

  const columns: Column<ChartOfAccountView>[] = [
    {
      key: 'accountCode',
      header: 'Code',
      render: (a) => <span className="font-mono text-xs">{a.accountCode}</span>,
    },
    { key: 'name', header: 'Name', render: (a) => <span className="font-medium">{a.name}</span> },
    {
      key: 'accountType',
      header: 'Type',
      secondary: true,
      render: (a) => <span className="text-muted-foreground">{a.accountType}</span>,
    },
    {
      key: 'normalBalance',
      header: 'Normal balance',
      align: 'right',
      render: (a) => a.normalBalance,
    },
  ];

  function renderBody() {
    if (isInitialLoad(accounts)) return <TableSkeleton columns={columns.length} />;
    if (accounts.status === 'error' && accounts.error && accounts.data === null) {
      return <ErrorPanel error={accounts.error} onRetry={() => void loadChartOfAccounts()} />;
    }
    const rows = accounts.data ?? [];
    if (rows.length === 0) {
      return <EmptyState title="No accounts" description="Nothing is seeded in this tenant yet." />;
    }
    return (
      <DataTable columns={columns} rows={rows} rowKey={(a) => a.accountCode} caption="Chart of accounts" />
    );
  }

  return (
    <>
      <PageHeader
        title="Chart of accounts"
        description="Placeholder accounts pending Finance sign-off. Seeded, not authored through any API."
      />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">{renderBody()}</div>
      </div>
    </>
  );
}
