import { useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectJournalEntryDetail, useFinaccountingStore } from '@/store/finaccountingStore';

/** Where this record lives. GL postings are staff-only -- there is no agents mount. */
const BREADCRUMB = [{ label: 'GL postings', to: '/staff/gl-postings' }];

/**
 * The "acts" half of drawer-previews-page-acts, though there is nothing to
 * act on: no endpoint here ever edits a journal entry, by design (a
 * correction is a future reversal entry, deferred to a later milestone).
 * `postings` always carries exactly two legs in M9 (one DR, one CR, equal
 * totals), enforced by `JournalEntry`'s own invariant, so this page's job is
 * simply to show them plainly.
 */
export function GlPostingDetailPage() {
  const { journalEntryId = '' } = useParams();

  const detail = useFinaccountingStore(selectJournalEntryDetail(journalEntryId));
  const loadDetail = useFinaccountingStore((s) => s.loadDetail);

  useEffect(() => {
    if (journalEntryId) void loadDetail(journalEntryId);
  }, [journalEntryId, loadDetail]);

  const entry = detail.data;

  if (isInitialLoad(detail)) {
    return <LoadingBlock label="Loading journal entry" />;
  }

  if (detail.data === null && detail.status === 'error' && detail.error) {
    return (
      <>
        {/* The bar renders on the error path too. A record that fails to load used to lose its
            heading and its way out along with its data, so the reader was left holding an
            error panel with nothing above it -- and it looked like a different console from
            the one that appears when the same record loads. */}
        <PageHeader breadcrumb={BREADCRUMB} title="Journal entry" />
        <div className="px-6 pt-6">
          <ErrorPanel error={detail.error} onRetry={() => void loadDetail(journalEntryId)} />
        </div>
      </>
    );
  }

  return (
    <>
      <PageHeader
        // Replaces a ghost button with a back arrow that sat in its own strip ABOVE the bar,
        // doing a breadcrumb's job while pushing the sticky bar 44px down every record.
        breadcrumb={BREADCRUMB}
        title={entry?.sourceEvent ?? 'Journal entry'}
        description={entry?.policyNumber ? <span className="font-mono text-xs">{entry.policyNumber}</span> : undefined}
      />

      {entry && (
        <div className="grid gap-5 px-6 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
          <div className="space-y-5">
            <section className="rounded-lg border border-border bg-surface">
              <div className="border-b border-border px-4 py-3">
                <h2 className="text-sm font-semibold">Postings</h2>
                <p className="text-xs text-muted-foreground">Always exactly two legs -- one DR, one CR, equal totals</p>
              </div>
              <ul className="divide-y divide-border">
                {entry.postings.map((p) => (
                  <li key={p.postingId} className="flex items-center justify-between px-4 py-2.5 text-sm">
                    <span className="font-mono text-xs">{p.accountCode}</span>
                    <span className="flex items-center gap-3">
                      <span className="text-xs text-muted-foreground">{p.direction}</span>
                      <span className="font-medium">{formatMoney(p.amount)}</span>
                    </span>
                  </li>
                ))}
              </ul>
            </section>
          </div>

          <div className="space-y-5">
            <section className="rounded-lg border border-border bg-surface">
              <div className="border-b border-border px-4 py-3">
                <h2 className="text-sm font-semibold">Entry</h2>
              </div>
              <dl className="px-4 pb-2">
                <Field label="Period" value={entry.period} />
                <Field label="Posted" value={formatInstant(entry.postedAt)} />
                <Field label="Source ref" value={<span className="font-mono text-xs">{entry.sourceRef}</span>} />
              </dl>
            </section>
          </div>
        </div>
      )}
    </>
  );
}

