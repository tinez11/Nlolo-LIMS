import { useEffect, useRef } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { downloadExtract, downloadResultsTemplate } from '@/api/ifrs17Engine';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatInstant } from '@/lib/dates';
import { saveBlob } from '@/lib/download';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useEngineStore } from '@/store/engineStore';
import { RUN_STATUS_LABEL, previousMonth } from './enginePeriod';

/**
 * The IFRS 17 engine for one period (IFRS 17 I5a, month-end steps 6 and 7): make the extract the engine is sent, upload
 * what it returns, and follow each run to its approval, posting and reconciliation. The period must be closing.
 */
export function EnginePage() {
  const [params, setParams] = useSearchParams();
  const period = params.get('period') ?? previousMonth();
  const extracts = useEngineStore((s) => s.extracts);
  const runs = useEngineStore((s) => s.runs);
  const loadPeriod = useEngineStore((s) => s.loadPeriod);
  const createExtract = useEngineStore((s) => s.createExtract);
  const upload = useEngineStore((s) => s.upload);
  const extracting = useEngineStore((s) => s.acting[`extract.${period}`]);
  const uploading = useEngineStore((s) => s.acting[`upload.${period}`]);
  const file = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (/^\d{4}-(0[1-9]|1[0-2])$/.test(period)) void loadPeriod(period);
  }, [period, loadPeriod]);

  return (
    <>
      <PageHeader
        title="IFRS 17 engine"
        description="Month-end steps 6 and 7: the extract the engine is sent, its results loaded through 9160, the ledger reconciled."
      />
      <div className="space-y-4 px-6 pb-6">
        <div className="flex flex-wrap items-end gap-3">
          <div className="w-40">
            <FormField label="Period">
              <Input inputSize="sm" value={period} placeholder="YYYY-MM"
                onChange={(e) => setParams({ period: e.target.value.trim() })} />
            </FormField>
          </div>
          <Button type="button" size="sm" variant="outline"
            onClick={async () => saveBlob(await downloadResultsTemplate(), 'ifrs17-engine-results-template.xlsx')}>
            Download results template
          </Button>
        </div>

        <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Extracts">
          <div className="flex items-center justify-between">
            <p className="text-xs font-medium">Extracts (step 6)</p>
            <Button type="button" size="sm" variant="primary" disabled={extracting?.status === 'loading'}
              onClick={() => void createExtract(period)}>
              Create extract
            </Button>
          </div>
          {extracting?.status === 'error' && extracting.error && <InlineError error={extracting.error} />}
          {isInitialLoad(extracts) ? <LoadingBlock /> : (extracts.data ?? []).length === 0 ? (
            <p className="text-sm text-muted-foreground">
              No extract yet. The period must be closing, with every event posted.
            </p>
          ) : (
            <table className="w-full text-sm" aria-label="Extracts">
              <tbody>
                {(extracts.data ?? []).map((x) => (
                  <tr key={x.extractId} className="border-t border-border">
                    <td className="py-1 pr-3 font-medium">{x.period} #{x.number}</td>
                    <td className="py-1 pr-3 text-xs text-muted-foreground">
                      {/* The results file needs a closing row for every group the extract names: show which. */}
                      <details>
                        <summary className="cursor-pointer">
                          {x.groups.length} groups · {x.cashFlowRows} cash-flow rows · {x.policyRows} policies
                        </summary>
                        <ul className="mt-1 font-mono" aria-label={`Groups of ${x.period} #${x.number}`}>
                          {x.groups.map((g) => <li key={g}>{g}</li>)}
                        </ul>
                      </details>
                    </td>
                    <td className="py-1 pr-3 text-xs text-muted-foreground">{formatInstant(x.createdAt)}</td>
                    <td className="py-1 text-right">
                      <Button type="button" size="sm" variant="outline"
                        onClick={async () => saveBlob(await downloadExtract(x.extractId), `ifrs17-extract-${x.period}-${x.number}.xlsx`)}>
                        Download extract
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Engine runs">
          <div className="flex items-center justify-between">
            <p className="text-xs font-medium">Engine runs (step 7)</p>
            <div>
              <input ref={file} type="file" accept=".xlsx" className="sr-only" aria-label="Results file"
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  if (f) void upload(period, f);
                  e.target.value = '';
                }} />
              <Button type="button" size="sm" variant="primary" disabled={uploading?.status === 'loading'}
                onClick={() => file.current?.click()}>
                Upload results
              </Button>
            </div>
          </div>
          {uploading?.status === 'error' && uploading.error && <InlineError error={uploading.error} />}
          {runs.status === 'error' && runs.error && runs.data === null ? (
            <ErrorPanel error={runs.error} onRetry={() => void loadPeriod(period)} />
          ) : isInitialLoad(runs) ? <LoadingBlock /> : (runs.data ?? []).length === 0 ? (
            <EmptyState title="No engine run" description="Upload the engine's results, filled in the template." />
          ) : (
            <table className="w-full text-sm" aria-label="Engine runs">
              <thead>
                <tr className="text-left text-xs text-muted-foreground">
                  <th className="py-1 pr-3 font-normal">Engine reference</th>
                  <th className="py-1 pr-3 font-normal">Status</th>
                  <th className="py-1 pr-3 font-normal">Uploaded</th>
                </tr>
              </thead>
              <tbody>
                {(runs.data ?? []).map((r) => (
                  <tr key={r.runId} className="border-t border-border">
                    <td className="py-1 pr-3">
                      <Link className="font-medium underline-offset-2 hover:underline" to={`runs/${r.runId}`}>
                        {r.engineReference ?? r.fileName ?? r.runId}
                      </Link>
                    </td>
                    <td className="py-1 pr-3">
                      {RUN_STATUS_LABEL[r.status] ?? r.status}
                      {r.status === 'REJECTED' && r.errors.length > 0 && (
                        <span className="ml-1 text-xs text-status-danger-fg">({r.errors.length} errors)</span>
                      )}
                    </td>
                    <td className="py-1 pr-3 text-xs text-muted-foreground">{formatInstant(r.uploadedAt)} by {r.uploadedBy}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>
    </>
  );
}
