import { ArrowRight } from 'lucide-react';
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Sheet, SheetBody, SheetContent, SheetFooter, SheetHeader } from '@/components/ui/sheet';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectReturnDetail, useRegreportingStore } from '@/store/regreportingStore';
import { ReturnLinesList } from './ReturnLinesList';

/** The preview half of drawer-previews-page-acts -- there is no "acts" half
 *  here either, matching GlPostingDrawer: a generated return is replaced
 *  wholesale by regenerating it, never edited in place. */
export function RegulatoryReturnDrawer({
  returnId,
  onClose,
}: {
  returnId: string | null;
  onClose: () => void;
}) {
  const detail = useRegreportingStore(selectReturnDetail(returnId ?? ''));
  const loadDetail = useRegreportingStore((s) => s.loadDetail);

  useEffect(() => {
    if (returnId) void loadDetail(returnId);
  }, [returnId, loadDetail]);

  const view = detail.data;

  return (
    <Sheet
      open={returnId !== null}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <SheetContent aria-label="Regulatory return preview">
        <SheetHeader
          title={view?.returnType ?? 'Regulatory return'}
          subtitle={view?.period}
          action={view?.status && <StatusBadge kind="regulatoryReturn" value={view.status} />}
        />

        <SheetBody>
          {isInitialLoad(detail) && <LoadingBlock />}

          {detail.status === 'error' && detail.error && detail.data === null && (
            <ErrorPanel error={detail.error} {...(returnId ? { onRetry: () => void loadDetail(returnId) } : {})} />
          )}

          {view && <ReturnLinesList lines={view.lines} />}
        </SheetBody>

        {returnId && (
          <SheetFooter>
            <Button asChild variant="outline" className="w-full justify-between">
              <Link to={`../regulatory-returns/${encodeURIComponent(returnId)}`} relative="path">
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
