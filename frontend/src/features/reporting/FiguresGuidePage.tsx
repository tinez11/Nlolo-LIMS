import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getFigureDefinitions, type FigureDefinition, type FigureGroup, type ReportDefinition } from '@/api/reporting';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import type { ApiError } from '@/lib/http';
import { useReportingStore } from '@/store/reportingStore';
import { sourcesInWords } from './freshness';
import { reportAreas } from './reportAreas';

/** How a report's dates work, in words. */
const PERIOD_WORDS: Record<ReportDefinition['dateFilterKind'], string> = {
  AS_OF: 'On one day',
  PERIOD: 'Between two days',
  AS_OF_AND_PERIOD: 'On one day, and between two days',
  MONTH: 'One month',
  MONTH_RANGE: 'A run of months',
  COHORT: 'Policies that started in a run of months, followed forward',
};

/**
 * What the figures mean (2026-11-11, analytics plan 2; PRD 19, decision K1). Read-only: every word comes from the
 * platform's code -- the Collections definitions written beside the code that works them out, and each report's own
 * definition (what it reads, its dates, what it compares with). A formula change is a code change that changes these
 * words with it.
 */
export function FiguresGuidePage() {
  const catalogue = useReportingStore((s) => s.catalogue);
  const loadCatalogue = useReportingStore((s) => s.loadCatalogue);
  const [groups, setGroups] = useState<FigureGroup[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    if (catalogue.data === null && catalogue.status !== 'loading') void loadCatalogue();
  }, [catalogue.data, catalogue.status, loadCatalogue]);

  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let live = true;
    getFigureDefinitions().then(
      (g) => { if (live) { setGroups(g); setError(null); } },
      (e: ApiError) => { if (live) setError(e); },
    );
    return () => { live = false; };
  }, [attempt]);

  const definitions = catalogue.data ?? [];
  const byKey = new Map(definitions.map((d) => [d.key, d]));
  const areas = reportAreas(definitions)
    .map((a) => ({ ...a, entries: a.entries.filter((e) => !e.to && byKey.has(e.key)) }))
    .filter((a) => a.entries.length > 0);

  return (
    <>
      <PageHeader
        title="What the figures mean"
        breadcrumb={[{ label: 'Reports', to: '/staff/reports' }]}
        description="How each figure is worked out, read from the platform's own code. A change to a formula changes these words with it."
      />
      <div className="space-y-8 px-6 pb-10">
        {error ? (
          <ErrorPanel error={error} onRetry={() => setAttempt((n) => n + 1)} />
        ) : groups === null ? (
          <LoadingBlock label="Reading the definitions" />
        ) : (
          groups.map((group) => (
            <section key={group.title} aria-labelledby={`group-${group.title}`} className="space-y-3">
              <h2 id={`group-${group.title}`} className="font-display text-title font-semibold text-heading">
                {group.title}
              </h2>
              <div className="grid gap-3 lg:grid-cols-2">
                {group.figures.map((f) => <FigureCard key={f.key} figure={f} />)}
              </div>
            </section>
          ))
        )}

        <section aria-labelledby="reports-heading" className="space-y-3">
          <h2 id="reports-heading" className="font-display text-title font-semibold text-heading">Reports</h2>
          {catalogue.status === 'error' && catalogue.error && catalogue.data === null ? (
            <ErrorPanel error={catalogue.error} onRetry={() => void loadCatalogue()} />
          ) : catalogue.data === null ? (
            <LoadingBlock label="Reading the reports" />
          ) : (
            areas.map((area) => (
              <div key={area.id} className="space-y-2">
                <h3 className="text-sm font-semibold text-muted-foreground">{area.title}</h3>
                <ul className="divide-y divide-border rounded-xl border border-border bg-surface shadow-card">
                  {area.entries.map((e) => {
                    const d = byKey.get(e.key)!;
                    return (
                      <li key={d.key} className="max-w-prose space-y-1.5 px-5 py-4 lg:max-w-none">
                        <Link to={`/staff/reports/${d.key}`} className="text-sm font-semibold text-foreground hover:underline">
                          {d.title}
                        </Link>
                        <p className="max-w-[70ch] text-sm text-foreground">{d.description}</p>
                        <dl className="grid max-w-[70ch] grid-cols-[7rem_1fr] gap-x-3 gap-y-0.5 text-sm">
                          <dt className="text-label">Period</dt>
                          <dd>{PERIOD_WORDS[d.dateFilterKind]}</dd>
                          {d.sources.length > 0 && (
                            <>
                              <dt className="text-label">Comes from</dt>
                              <dd>{capitalised(sourcesInWords(d.sources))}</dd>
                            </>
                          )}
                          {d.comparesPeriods && (
                            <>
                              <dt className="text-label">Compares with</dt>
                              <dd>The period before, or the same period last year</dd>
                            </>
                          )}
                        </dl>
                        {d.figures.length > 0 && (
                          <div className="grid gap-3 pt-2 lg:grid-cols-2">
                            {d.figures.map((f) => <FigureCard key={f.key} figure={f} flat />)}
                          </div>
                        )}
                      </li>
                    );
                  })}
                </ul>
              </div>
            ))
          )}
        </section>
      </div>
    </>
  );
}

const capitalised = (s: string) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : s);

/** One figure: its name, what it is in one line, then how it is counted -- mockup screen 3's example card. */
function FigureCard({ figure, flat = false }: { figure: FigureDefinition; flat?: boolean }) {
  return (
    <article
      aria-labelledby={`figure-${figure.key}`}
      className={flat
        ? 'space-y-2 rounded-lg border border-border px-4 py-3'
        : 'space-y-2 rounded-xl border border-border bg-surface px-5 py-4 shadow-card'}
    >
      <h3 id={`figure-${figure.key}`} className="font-display text-sm font-semibold text-heading">{figure.name}</h3>
      <p className="text-sm text-foreground">{figure.meaning}</p>
      <dl className="grid grid-cols-[6.5rem_1fr] gap-x-3 gap-y-1 text-sm">
        <dt className="text-label">Counts</dt>
        <dd>{figure.counts}</dd>
        <dt className="text-label">Leaves out</dt>
        <dd>{figure.leavesOut}</dd>
        <dt className="text-label">Comes from</dt>
        <dd>{figure.comesFrom}</dd>
        <dt className="text-label">Dates</dt>
        <dd>{figure.dateBasis}</dd>
      </dl>
    </article>
  );
}
