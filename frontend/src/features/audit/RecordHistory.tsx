import { useEffect, useState } from 'react';
import { Pager } from '@/components/DataTable';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { idle } from '@/store/createResourceSlice';
import { useActivityStore } from '@/store/activityStore';
import { ActivityLine } from './ActivityLine';

/**
 * A record's Activity (2026-11-14): the activity log filtered to one policy, client, claim or agent, newest first,
 * with what the platform did to it by itself. Flat: every page puts it inside its own Panel, and a card never sits
 * inside a card (DESIGN.md).
 */
export function RecordHistory({ recordType, recordId }: { recordType: string; recordId: string }) {
  const [page, setPage] = useState(0);
  const [openId, setOpenId] = useState<string | null>(null);
  const history = useActivityStore((s) => s.histories[`${recordType}:${recordId}`]) ?? idle();
  const loadHistory = useActivityStore((s) => s.loadHistory);

  useEffect(() => {
    void loadHistory(recordType, recordId, page);
  }, [loadHistory, recordType, recordId, page]);

  const data = history.data;
  if (history.status === 'error' && history.error && !data) {
    return <ErrorPanel error={history.error} onRetry={() => void loadHistory(recordType, recordId, page)} />;
  }
  if (!data) return <TableSkeleton columns={5} />;
  if (data.items.length === 0) {
    return <EmptyState title="Nothing recorded on this yet" description="Changes made from now on appear here." />;
  }
  return (
    <div>
      <ul className="divide-y divide-border" aria-label="Activity">
        {data.items.map((line) => (
          <ActivityLine
            key={line.id}
            line={line}
            open={openId === line.id}
            onToggle={() => setOpenId(openId === line.id ? null : line.id)}
            showRecord={false}
          />
        ))}
      </ul>
      <Pager page={data.page} busy={history.status === 'loading'} onPageChange={setPage} />
    </div>
  );
}
