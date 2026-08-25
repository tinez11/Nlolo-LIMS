import { ArrowLeft } from 'lucide-react';
import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/AppShell';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectTreatyDetail, useReinsuranceStore } from '@/store/reinsuranceStore';

/**
 * The "acts" half of drawer-previews-page-acts, though there is nothing to
 * act on here: no endpoint updates or retires a treaty once created (`status`
 * moves ACTIVE -> EXPIRED, if it ever does, with no visible mechanism in this
 * codebase -- most plausibly a scheduled sweep past `effectiveTo`, but nothing
 * confirms one exists). This page is therefore read-only by platform
 * constraint, not by an unfinished feature.
 */
export function TreatyDetailPage() {
  const { treatyId = '' } = useParams();

  const detail = useReinsuranceStore(selectTreatyDetail(treatyId));
  const loadDetail = useReinsuranceStore((s) => s.loadDetail);

  useEffect(() => {
    if (treatyId) void loadDetail(treatyId);
  }, [treatyId, loadDetail]);

  const treaty = detail.data;

  if (isInitialLoad(detail)) {
    return <LoadingBlock label="Loading treaty" />;
  }

  if (detail.data === null && detail.status === 'error' && detail.error) {
    return (
      <div className="px-6 pt-6">
        <BackLink />
        <ErrorPanel error={detail.error} onRetry={() => void loadDetail(treatyId)} />
      </div>
    );
  }

  return (
    <>
      <div className="px-6 pt-6">
        <BackLink />
      </div>

      <PageHeader
        title={treaty?.reinsurerName ?? 'Treaty'}
        description={treaty?.treatyType?.replace(/_/g, ' ')}
        actions={treaty?.status && <StatusBadge kind="treaty" value={treaty.status} />}
      />

      {treaty && (
        <div className="max-w-md px-6 pb-8">
          <section className="rounded-lg border border-border bg-surface">
            <dl className="px-4 pb-2">
              <Field label="Retention limit" value={formatMoney(treaty.retentionLimit)} emphasis />
              {treaty.cessionPercent && (
                <Field
                  label="Cession"
                  value={`${treaty.cessionPercent}%`}
                  note="Only QUOTA_SHARE treaties carry a cession percent"
                />
              )}
              <Field label="Effective from" value={formatDate(treaty.effectiveFrom)} />
              <Field
                label="Effective to"
                value={treaty.effectiveTo ? formatDate(treaty.effectiveTo) : 'Open-ended'}
              />
              {treaty.treatyType === 'XOL' && (
                <Field
                  label="Cession at issuance"
                  value="None"
                  note="Excess-of-loss cedes nothing on new business -- it participates only in claim recovery"
                />
              )}
            </dl>
          </section>
        </div>
      )}
    </>
  );
}

function BackLink() {
  return (
    <Button asChild variant="ghost" size="sm" className="-ml-2">
      <Link to=".." relative="path">
        <ArrowLeft />
        All treaties
      </Link>
    </Button>
  );
}
