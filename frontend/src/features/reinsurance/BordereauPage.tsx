import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import type { BordereauLine } from '@/api/types';
import { DataTable, type Column } from '@/components/DataTable';
import { DetailLayout } from '@/components/DetailLayout';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectBordereau, useReinsuranceStore } from '@/store/reinsuranceStore';
import { formatPeriod } from './formatPeriod';

const LINE_TYPE: Record<string, string> = {
  PREMIUM: 'Policy',
  XOL_PREMIUM: 'XOL premium',
  RECOVERY: 'Recovery (matched)',
};

/**
 * One treaty's bordereau for one month (IFRS 17 I3c): every policy charged, the XOL twelfth, and the recoveries
 * recorded that month. Read-only and final -- the month-end job wrote it and it posted one journal (K-01 ceded
 * premium, K-02 commission) dated the month's last day. Recoveries posted when their claims were approved (B-05);
 * they are listed here only to be matched with the reinsurer.
 */
export function BordereauPage() {
  const { treatyId = '', bordereauId = '' } = useParams();
  const bordereau = useReinsuranceStore(selectBordereau(bordereauId));
  const loadBordereau = useReinsuranceStore((s) => s.loadBordereau);

  useEffect(() => {
    if (bordereauId) void loadBordereau(bordereauId);
  }, [bordereauId, loadBordereau]);

  const breadcrumb = [
    { label: 'Treaties', to: '/staff/treaties' },
    { label: 'Treaty', to: `/staff/treaties/${treatyId}` },
  ];

  if (isInitialLoad(bordereau)) return <LoadingBlock label="Loading bordereau" />;
  if (bordereau.data === null && bordereau.status === 'error' && bordereau.error) {
    return (
      <>
        <PageHeader breadcrumb={breadcrumb} title="Bordereau" />
        <div className="px-6 pt-6">
          <ErrorPanel error={bordereau.error} onRetry={() => void loadBordereau(bordereauId)} />
        </div>
      </>
    );
  }
  const b = bordereau.data;
  if (!b) return null;

  const money = (amount: string | null | undefined) =>
    amount == null ? '—' : formatMoney({ amount, currencyCode: b.currency });

  const columns: Column<BordereauLine>[] = [
    { key: 'type', header: 'Line', render: (l) => LINE_TYPE[l.type] ?? l.type },
    {
      key: 'ref',
      header: 'Policy / claim',
      render: (l) => (
        <span className="font-mono">{l.policyNumber ?? (l.claimId ? `claim ${l.claimId.slice(0, 8)}` : '—')}</span>
      ),
    },
    {
      key: 'share',
      header: 'Share',
      align: 'right',
      secondary: true,
      render: (l) => (l.premiumShare ? `${(Number(l.premiumShare) * 100).toFixed(2)}%` : '—'),
    },
    { key: 'policyPremium', header: 'Policy premium', align: 'right', secondary: true, render: (l) => money(l.policyPremium) },
    { key: 'premium', header: 'Ceded premium', align: 'right', render: (l) => money(l.premium) },
    { key: 'commission', header: 'Commission', align: 'right', render: (l) => money(l.commission) },
    { key: 'recovery', header: 'Recovery', align: 'right', render: (l) => money(l.recovery) },
  ];

  return (
    <>
      <PageHeader
        breadcrumb={breadcrumb}
        title={`Bordereau ${formatPeriod(b.period)}`}
        description="Final once written. Posted as one journal on the month's last day."
      />
      <DetailLayout
        record={
          <Panel title="Totals">
            <dl className="px-4 pb-2">
              <Field label="Ceded premium" value={money(b.premium)} emphasis note="K-01: Dr 1436 / Cr 1430" />
              <Field label="Commission" value={money(b.commission)} note="K-02: Dr 1431 / Cr 1436" />
              <Field label="Recoveries matched" value={money(b.recoveries)} note="Posted at claim approval (B-05)" />
              <Field label="Policies charged" value={String(b.policyCount)} />
              <Field label="Written" value={formatInstant(b.createdAt)} />
            </dl>
            <p className="px-4 pb-3 text-xs">
              <Link className="underline" to={`/staff/gl-postings?period=${b.period}&accountCode=1430`}>
                Postings to 1430 in {formatPeriod(b.period)}
              </Link>
            </p>
          </Panel>
        }
      >
        <Panel title="Lines">
          {b.lines.length === 0 ? (
            <EmptyState
              title="Nothing charged this month"
              description="No ceded policy was on risk and paying premiums, and the treaty carries no XOL premium."
            />
          ) : (
            <DataTable
              columns={columns}
              rows={b.lines}
              rowKey={(l) => `${l.type}-${l.policyNumber ?? ''}-${l.claimId ?? ''}`}
              caption={`Lines of the ${formatPeriod(b.period)} bordereau`}
            />
          )}
        </Panel>
      </DetailLayout>
    </>
  );
}
