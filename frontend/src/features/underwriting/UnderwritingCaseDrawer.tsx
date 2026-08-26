import { ArrowRight } from 'lucide-react';
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Sheet, SheetBody, SheetContent, SheetFooter, SheetHeader } from '@/components/ui/sheet';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectCase, useUnderwritingStore } from '@/store/underwritingStore';

/**
 * The preview half of drawer-previews-page-acts, same convention every other
 * list page on this console follows (see TreatyDrawer, which -- like this one
 * -- has no mutating action inside it either; both are "the compact view",
 * not "the read-only twin of a page that also acts").
 */
export function UnderwritingCaseDrawer({
  caseId,
  onClose,
}: {
  caseId: string | null;
  onClose: () => void;
}) {
  const detail = useUnderwritingStore(selectCase(caseId ?? ''));
  const loadCase = useUnderwritingStore((s) => s.loadCase);

  useEffect(() => {
    if (caseId) void loadCase(caseId);
  }, [caseId, loadCase]);

  const uwCase = detail.data;

  return (
    <Sheet
      open={caseId !== null}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <SheetContent aria-label="Underwriting case preview">
        <SheetHeader
          title="Underwriting case"
          subtitle={uwCase?.applicantPartyId}
          action={uwCase?.status ? <StatusBadge kind="underwritingCase" value={uwCase.status} /> : undefined}
        />

        <SheetBody>
          {isInitialLoad(detail) && <LoadingBlock />}

          {detail.status === 'error' && detail.error && detail.data === null && (
            <ErrorPanel
              error={detail.error}
              {...(caseId ? { onRetry: () => void loadCase(caseId) } : {})}
            />
          )}

          {uwCase && (
            <dl className="space-y-0">
              <Field label="Applicant" value={<span className="font-mono text-xs">{uwCase.applicantPartyId}</span>} />
              <Field label="Product" value={<span className="font-mono text-xs">{uwCase.productId}</span>} />
              <Field
                label="Decision"
                value={uwCase.decisionOutcome ?? 'Not yet decided'}
              />
            </dl>
          )}
        </SheetBody>

        {caseId && (
          <SheetFooter>
            <Button asChild variant="outline" className="w-full justify-between">
              <Link to={`../underwriting/${caseId}`} relative="path">
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
