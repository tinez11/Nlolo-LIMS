import { ArrowRight } from 'lucide-react';
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Sheet, SheetBody, SheetContent, SheetFooter, SheetHeader } from '@/components/ui/sheet';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectTreatyDetail, useReinsuranceStore } from '@/store/reinsuranceStore';

/**
 * The preview half of drawer-previews-page-acts. Read-only -- there is no
 * mutating action on a treaty at all once created (no update/retire
 * endpoint exists), so unlike Policies/Claims this isn't "the read-only twin
 * of a page that also acts," it's simply the compact view.
 */
export function TreatyDrawer({
  treatyId,
  onClose,
}: {
  treatyId: string | null;
  onClose: () => void;
}) {
  const detail = useReinsuranceStore(selectTreatyDetail(treatyId ?? ''));
  const loadDetail = useReinsuranceStore((s) => s.loadDetail);

  useEffect(() => {
    if (treatyId) void loadDetail(treatyId);
  }, [treatyId, loadDetail]);

  const treaty = detail.data;

  return (
    <Sheet
      open={treatyId !== null}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <SheetContent aria-label="Treaty preview">
        <SheetHeader
          title={treaty?.reinsurerName ?? 'Treaty'}
          subtitle={treaty?.treatyType}
          action={treaty?.status ? <StatusBadge kind="treaty" value={treaty.status} /> : undefined}
        />

        <SheetBody>
          {isInitialLoad(detail) && <LoadingBlock />}

          {detail.status === 'error' && detail.error && detail.data === null && (
            <ErrorPanel
              error={detail.error}
              {...(treatyId ? { onRetry: () => void loadDetail(treatyId) } : {})}
            />
          )}

          {treaty && (
            <dl className="space-y-0">
              <Field label="Retention limit" value={formatMoney(treaty.retentionLimit)} emphasis />
              {treaty.cessionPercent && <Field label="Cession" value={`${treaty.cessionPercent}%`} />}
              <Field label="Effective from" value={formatDate(treaty.effectiveFrom)} />
              <Field label="Effective to" value={treaty.effectiveTo ? formatDate(treaty.effectiveTo) : 'Open-ended'} />
            </dl>
          )}
        </SheetBody>

        {treatyId && (
          <SheetFooter>
            <Button asChild variant="outline" className="w-full justify-between">
              <Link to={`../treaties/${encodeURIComponent(treatyId)}`} relative="path">
                Full detail
                <ArrowRight />
              </Link>
            </Button>
          </SheetFooter>
        )}
      </SheetContent>
    </Sheet>
  );
}
