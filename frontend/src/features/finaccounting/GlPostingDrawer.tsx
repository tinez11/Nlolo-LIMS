import { ArrowRight } from 'lucide-react';
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Sheet, SheetBody, SheetContent, SheetFooter, SheetHeader } from '@/components/ui/sheet';
import { formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectJournalEntryDetail, useFinaccountingStore } from '@/store/finaccountingStore';

/**
 * The preview half of drawer-previews-page-acts, though there is no "acts"
 * half to distinguish it from here: a journal entry is never edited, by
 * platform design (a correction is a future reversal entry, deferred). Kept
 * for the same "click a row, see a compact preview" language every other
 * list page on this console uses.
 */
export function GlPostingDrawer({
  journalEntryId,
  onClose,
}: {
  journalEntryId: string | null;
  onClose: () => void;
}) {
  const detail = useFinaccountingStore(selectJournalEntryDetail(journalEntryId ?? ''));
  const loadDetail = useFinaccountingStore((s) => s.loadDetail);

  useEffect(() => {
    if (journalEntryId) void loadDetail(journalEntryId);
  }, [journalEntryId, loadDetail]);

  const entry = detail.data;

  return (
    <Sheet
      open={journalEntryId !== null}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <SheetContent aria-label="Journal entry preview">
        <SheetHeader title={entry?.sourceEvent ?? 'Journal entry'} subtitle={entry?.period} />

        <SheetBody>
          {isInitialLoad(detail) && <LoadingBlock />}

          {detail.status === 'error' && detail.error && detail.data === null && (
            <ErrorPanel
              error={detail.error}
              {...(journalEntryId ? { onRetry: () => void loadDetail(journalEntryId) } : {})}
            />
          )}

          {entry && (
            <div className="space-y-3">
              <p className="text-xs text-muted-foreground">Posted {formatInstant(entry.postedAt)}</p>
              <ul className="divide-y divide-border rounded-md border border-border">
                {entry.postings.map((p) => (
                  <li key={p.postingId} className="flex items-center justify-between px-3 py-2 text-xs">
                    <span className="font-mono">{p.accountCode}</span>
                    <span className="flex items-center gap-2">
                      <span className="text-xs text-muted-foreground">{p.direction}</span>
                      <span className="font-medium">{formatMoney(p.amount)}</span>
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </SheetBody>

        {journalEntryId && (
          <SheetFooter>
            <Button asChild variant="outline" className="w-full justify-between">
              <Link to={`../gl-postings/${encodeURIComponent(journalEntryId)}`} relative="path">
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
