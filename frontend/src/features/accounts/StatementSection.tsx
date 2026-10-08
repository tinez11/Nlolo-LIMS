import { useEffect, useState } from 'react';
import { downloadStatementPdf } from '@/api/accumulation';
import { getSavingsStatement, type SavingsStatementView } from '@/api/documents';
import type { ApiError } from '@/lib/apiError';
import type { StatementRecordView } from '@/api/types';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { SavingsStatementTable } from '@/features/documents/SavingsStatementTable';
import { InlineError } from '@/components/InlineError';
import { LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate } from '@/lib/dates';
import { startMutation } from '@/lib/idempotency';
import { useAccumulationStore } from '@/store/accumulationStore';

/** The last full calendar year: the period the annual statement covers. */
function lastYear(): { from: string; to: string } {
  const year = new Date().getFullYear() - 1;
  return { from: `${year}-01-01`, to: `${year}-12-31` };
}

/**
 * A period of the account, reconciled, and filed as a PDF on request (spec §6).
 *
 * The figures are the server's: it builds the statement from the ledger and refuses to return one
 * that does not add up against its own sum. Filing the PDF returns the EXISTING statement when
 * nothing has been posted since -- the panel says "already filed" rather than pretending to have
 * made a new one. There is no automatic delivery to the customer; staff send the file.
 */
export function StatementSection({ policyNumber }: { policyNumber: string }) {
  const [period, setPeriod] = useState(lastYear);
  const [statement, setStatement] = useState<SavingsStatementView | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [loading, setLoading] = useState(false);
  const [filedNote, setFiledNote] = useState<string | null>(null);

  const records = useAccumulationStore((s) => s.statements[policyNumber]);
  const loadStatements = useAccumulationStore((s) => s.loadStatements);
  const fileStatement = useAccumulationStore((s) => s.fileStatement);
  const acting = useAccumulationStore((s) => s.acting[`statement.${policyNumber}`]);

  useEffect(() => {
    void loadStatements(policyNumber);
  }, [policyNumber, loadStatements]);

  const show = async () => {
    setLoading(true);
    setError(null);
    setFiledNote(null);
    try {
      setStatement(await getSavingsStatement(policyNumber, period.from, period.to));
    } catch (cause) {
      setStatement(null);
      setError(cause as ApiError);
    } finally {
      setLoading(false);
    }
  };

  const file = async () => {
    const known = new Set((records?.data ?? []).map((r) => r.statementId));
    const record = await fileStatement(policyNumber, period.from, period.to, startMutation());
    if (record) {
      setFiledNote(known.has(record.statementId)
        ? 'Already filed: nothing has been posted since, so this is the same statement.'
        : 'Filed as a PDF. It is listed below.');
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <FormField label="From">
          <DatePicker value={period.from} onChange={(iso) => iso && setPeriod((p) => ({ ...p, from: iso }))} />
        </FormField>
        <FormField label="To">
          <DatePicker value={period.to} onChange={(iso) => iso && setPeriod((p) => ({ ...p, to: iso }))} />
        </FormField>
        <Button size="sm" onClick={() => void show()} disabled={loading}>
          Show statement
        </Button>
        <Button size="sm" variant="outline" onClick={() => void file()} disabled={acting?.status === 'loading'}>
          Generate PDF
        </Button>
      </div>

      {error && <InlineError error={error} />}
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      {filedNote && (
        <p className="text-xs text-muted-foreground" role="status">
          {filedNote}
        </p>
      )}
      {loading && <LoadingBlock />}
      {statement && <SavingsStatementTable statement={statement} />}
      <FiledStatements records={records?.data ?? []} />
    </div>
  );
}

function FiledStatements({ records }: { records: StatementRecordView[] }) {
  const [downloadError, setDownloadError] = useState<ApiError | null>(null);
  if (records.length === 0) return null;

  const download = async (record: StatementRecordView) => {
    setDownloadError(null);
    try {
      const blob = await downloadStatementPdf(record.statementId);
      window.open(URL.createObjectURL(blob), '_blank', 'noopener');
    } catch (cause) {
      setDownloadError(cause as ApiError);
    }
  };

  return (
    <div>
      <p className="mb-1.5 text-xs font-medium">Filed statements</p>
      {downloadError && <InlineError error={downloadError} />}
      <div className="divide-y divide-border rounded-md border border-border">
        {records.map((record) => (
          <div key={record.statementId} className="flex items-center justify-between gap-2 px-4 py-2.5">
            <span className="text-sm">
              {formatDate(record.periodFrom)} to {formatDate(record.periodTo)}
              <span className="ml-2 text-xs text-muted-foreground">by {record.generatedBy}</span>
            </span>
            <Button size="sm" variant="ghost" onClick={() => void download(record)}>
              Download
            </Button>
          </div>
        ))}
      </div>
    </div>
  );
}
