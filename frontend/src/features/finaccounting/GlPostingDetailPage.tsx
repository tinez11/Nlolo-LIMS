import { useEffect } from 'react';
import { useParams } from 'react-router-dom';
import type { JournalSource, LineDimensions } from '@/api/types';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectJournalEntryDetail, useFinaccountingStore } from '@/store/finaccountingStore';

/** Where this record lives. GL postings are staff-only -- there is no agents mount. */
const BREADCRUMB = [{ label: 'GL postings', to: '/staff/gl-postings' }];

const SOURCE_LABEL: Record<JournalSource, string> = {
  EVENT: 'Event',
  SYSTEM: 'Platform',
  ENGINE_RUN: 'IFRS 17 engine run',
  MANUAL: 'Manual journal',
};

/** A line's recorded dimensions (posting guide 2.2), the ones present, in the guide's order. */
function dimensionText(d: LineDimensions | undefined): string {
  if (!d) return '';
  return [d.movementType, d.ifrs17Group, d.measurementModel, d.portfolio, d.channel, d.branch, d.fund,
    d.reference ? `${d.referenceType ?? 'ref'} ${d.reference}` : null]
    .filter((v): v is string => v != null && v !== '')
    .join(' · ');
}

/**
 * The "acts" half of drawer-previews-page-acts, though there is nothing to
 * act on: no endpoint ever edits a journal, by design (a correction is a
 * reversing journal). The database refuses a journal whose debits and credits
 * differ, so this page's job is simply to show its lines, their dimensions,
 * what wrote it and the accounting policy register version it was posted under.
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
            <Panel title="Postings" subtitle="Debits equal credits — the ledger refuses an unbalanced journal">
              <ul className="divide-y divide-border">
                {entry.postings.map((p) => {
                  const dims = dimensionText(p.dimensions);
                  return (
                    <li key={p.postingId} className="px-4 py-2.5 text-sm">
                      <div className="flex items-center justify-between">
                        <span className="font-mono text-xs">{p.accountCode}</span>
                        <span className="flex items-center gap-3">
                          <span className="text-xs text-muted-foreground">{p.direction}</span>
                          <span className="font-medium">{formatMoney(p.amount)}</span>
                        </span>
                      </div>
                      {dims && <p className="mt-0.5 font-mono text-xs text-subtle-foreground">{dims}</p>}
                    </li>
                  );
                })}
              </ul>
            </Panel>
          </div>

          <div className="space-y-5">
            <Panel title="Entry">
              <dl className="px-4 pb-2">
                <Field label="Period" value={entry.period} />
                <Field label="Posted" value={formatInstant(entry.postedAt)} />
                <Field label="Source" value={SOURCE_LABEL[entry.sourceType] ?? entry.sourceType} />
                <Field label="Policy register version" value={entry.policyRegisterVersion} />
                {entry.ruleVersion != null && <Field label="Posting rules" value={entry.ruleVersion} />}
                <Field label="Source ref" value={<span className="font-mono text-xs">{entry.sourceRef}</span>} />
              </dl>
            </Panel>
          </div>
        </div>
      )}
    </>
  );
}

