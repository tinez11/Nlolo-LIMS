import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useEffect } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import type { MonthEndStep } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { StaffName } from '@/components/StaffName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { formatInstant } from '@/lib/dates';
import { idle, isInitialLoad } from '@/store/createResourceSlice';
import { useLedgerControlsStore } from '@/store/ledgerControlsStore';
import { previousMonth } from './enginePeriod';
import { monthName, shiftMonth, STATE_WORDS, stepLink } from './monthEndSteps';

const PERIOD = /^\d{4}-(0[1-9]|1[0-2])$/;

/**
 * A month's close as the finance guide's eight steps (2026-10-27, finance visibility step 5): where the month stands,
 * who finished each step and when, what is next, and -- in the lock's own words -- what still stops it. It reads only;
 * each step opens the page where its work is done, on the same month.
 */
export function MonthEndPage() {
  const [params, setParams] = useSearchParams();
  const asked = params.get('period') ?? '';
  const period = PERIOD.test(asked) ? asked : previousMonth();
  const resource = useLedgerControlsStore((s) => s.monthEnd[period]) ?? idle();
  const load = useLedgerControlsStore((s) => s.loadMonthEnd);

  useEffect(() => {
    void load(period);
  }, [load, period]);

  const go = (by: number) => setParams({ period: shiftMonth(period, by) });

  function renderBody() {
    if (isInitialLoad(resource)) return <LoadingBlock label="Loading the month's close" />;
    if (!resource.data && resource.status === 'error' && resource.error) {
      return <ErrorPanel error={resource.error} onRetry={() => void load(period)} />;
    }
    const view = resource.data;
    if (!view) return null;
    const locked = view.status === 'LOCKED';
    return (
      <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_320px]">
        <Panel
          title={locked ? `${monthName(period)} is closed` : `Step ${view.current} of 8 is next`}
          subtitle="In the order the finance guide closes a month"
        >
          <ol className="px-4 pb-4" aria-label="Month-end steps">
            {view.steps.map((step, i) => (
              <StepRow
                key={step.key}
                step={step}
                period={period}
                current={step.number === view.current}
                last={i === view.steps.length - 1}
              />
            ))}
          </ol>
        </Panel>
        <Panel title={locked ? 'Locked' : 'What stops the lock'}>
          <div className="px-4 pb-4 text-sm">
            {locked ? (
              <p>
                Nothing more posts to {monthName(period)}. A mistake found now is corrected with a reversal in the month
                that is still open.
              </p>
            ) : view.blockers.length === 0 ? (
              <p>{view.status === 'CLOSING' ? 'Nothing. The month can be locked.' : 'Nothing yet, but the month is still open: start closing it first.'}</p>
            ) : (
              <ul className="list-disc space-y-1.5 pl-4" aria-label="What stops the lock">
                {view.blockers.map((b) => (
                  <li key={b}>{b}</li>
                ))}
              </ul>
            )}
          </div>
        </Panel>
      </div>
    );
  }

  return (
    <>
      <PageHeader
        title="Month-end close"
        description={`${monthName(period)}: the eight steps that close a month, where each stands, and what comes next.`}
        status={resource.data && <StatusBadge kind="accountingPeriod" value={resource.data.status} />}
        actions={
          <div className="flex items-center gap-1">
            <Button size="sm" variant="ghost" onClick={() => go(-1)} aria-label="Previous month">
              <ChevronLeft className="size-4" aria-hidden />
              {monthName(shiftMonth(period, -1))}
            </Button>
            <Button size="sm" variant="ghost" onClick={() => go(1)} aria-label="Next month">
              {monthName(shiftMonth(period, 1))}
              <ChevronRight className="size-4" aria-hidden />
            </Button>
          </div>
        }
      />
      <div className="px-6 pb-8">{renderBody()}</div>
    </>
  );
}

function StepRow({ step, period, current, last }: { step: MonthEndStep; period: string; current: boolean; last: boolean }) {
  const link = stepLink(step.key, period);
  const done = step.state === 'DONE';
  return (
    <li
      className="relative flex gap-3 pb-5 last:pb-0"
      aria-label={`Step ${step.number}: ${step.title}`}
      aria-current={current ? 'step' : undefined}
    >
      {/* The rail joining the steps: what has been done, then what has not. */}
      {!last && (
        <span aria-hidden className={cn('absolute left-[13px] top-7 bottom-0 w-px', done ? 'bg-status-success-fg/40' : 'bg-border')} />
      )}
      <span
        aria-hidden
        className={cn(
          'relative z-10 flex size-7 shrink-0 items-center justify-center rounded-full text-xs font-semibold',
          done
            ? 'bg-status-success-bg text-status-success-fg'
            : current
              ? 'bg-accent text-accent-foreground'
              : 'border border-border bg-surface text-muted-foreground',
        )}
      >
        {step.number}
      </span>
      <div className="min-w-0 flex-1 pt-0.5">
        <div className="flex flex-wrap items-center gap-2">
          <span className={cn('text-sm', current ? 'font-semibold' : 'font-medium')}>{step.title}</span>
          <StatusBadge kind="monthEndStep" value={step.state} label={STATE_WORDS[step.state]} />
        </div>
        <p className="mt-0.5 text-sm text-muted-foreground">{step.detail}</p>
        <p className="mt-0.5 flex flex-wrap gap-x-2 text-xs text-muted-foreground">
          {step.by && (
            <span>
              {step.by === 'system' ? 'The platform' : <StaffName subject={step.by} />}
              {step.at && <> · {formatInstant(step.at)}</>}
            </span>
          )}
          {!done && (
            <Link className="underline hover:text-foreground" to={link.to}>
              Open {link.label}
            </Link>
          )}
        </p>
      </div>
    </li>
  );
}
