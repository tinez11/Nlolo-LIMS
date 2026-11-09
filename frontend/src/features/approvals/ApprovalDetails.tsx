import { Link } from 'react-router-dom';
import type { ApprovalGroup, ApprovalRow } from '@/api/approvals';
import { Field } from '@/components/Field';
import { Button } from '@/components/ui/button';
import { Sheet, SheetBody, SheetContent, SheetFooter, SheetHeader } from '@/components/ui/sheet';
import { formatMoney } from '@/lib/money';
import { fromList } from '@/lib/returnCrumbs';
import { approvalLink } from './approvalLinks';
import { askedBy, overdueAfterDays, waiting } from './approvalWords';

/**
 * Every fact the inbox holds on one waiting request, in a drawer (2026-11-09, the user: the page "doesn't show more
 * details"). The drawer previews; the page acts -- approving money stays on the page that owns it, which shows the
 * whole record before the second signature, so the drawer only leads there.
 */
export function ApprovalDetails({ group, row, onClose }: {
  group: ApprovalGroup;
  row: ApprovalRow | null;
  onClose: () => void;
}) {
  const to = row ? approvalLink(group.kind, row) : null;
  const age = row ? waiting(group.kind, row.requestedAt) : null;
  return (
    <Sheet open={row !== null} onOpenChange={(open) => { if (!open) onClose(); }}>
      <SheetContent>
        {row && (
          <>
            <SheetHeader title={row.summary} subtitle={group.label} />
            <SheetBody>
              <dl>
                <Field label="Amount" value={row.amount ? formatMoney(row.amount) : '—'} emphasis />
                <Field label="Client" value={row.clientName ?? '—'} />
                <Field
                  label="Policy"
                  value={row.policyNumber ? (
                    <Link
                      to={fromList(`/staff/policies/${encodeURIComponent(row.policyNumber)}`, 'approvals')}
                      className="underline underline-offset-2 hover:text-foreground"
                    >
                      {row.policyNumber}
                    </Link>
                  ) : '—'}
                />
                {row.detail && <Field label="Details" value={row.detail} />}
                <Field label="Asked by" value={askedBy(row)} />
                <Field
                  label="Waiting"
                  value={age ? `${age.words}, since ${age.since}` : '—'}
                  {...(age?.overdue
                    ? { note: `Overdue: this kind should wait no more than ${overdueAfterDays(group.kind)} days.` }
                    : {})}
                />
                <Field label="Reference" value={<span className="break-all">{row.reference}</span>} />
              </dl>
            </SheetBody>
            {to && (
              <SheetFooter>
                <div className="flex justify-end gap-2">
                  <Button variant="ghost" onClick={onClose}>Close</Button>
                  <Button asChild variant="primary">
                    <Link to={to}>{row.yours ? 'Open it' : `${group.act} on its page`}</Link>
                  </Button>
                </div>
              </SheetFooter>
            )}
          </>
        )}
      </SheetContent>
    </Sheet>
  );
}
