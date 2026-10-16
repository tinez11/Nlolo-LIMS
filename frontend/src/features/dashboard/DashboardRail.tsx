import { ChevronRight, CircleAlert, Info } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Panel } from '@/components/Panel';
import { cn } from '@/lib/cn';

/**
 * The dashboard's right-hand column: what needs attention, what this role can do, and what is deliberately missing.
 *
 * Not a record rail. DESIGN.md's Record-Rail Rule keeps ACTS out of the 320px rail on a record page, because a
 * summary column is read as facts and an act read as a fact is taken without reading. These quick actions are links
 * into the screens that own each act -- every one of which has its own confirmation -- not acts in their own right.
 */
export interface Alert {
  tone: 'warning' | 'danger' | 'calm';
  text: ReactNode;
}

export interface QuickAction {
  label: string;
  description: string;
  to: string;
}

const TONE: Record<Alert['tone'], string> = {
  warning: 'bg-status-warning-bg text-status-warning-fg',
  danger: 'bg-status-danger-bg text-status-danger-fg',
  calm: 'border border-border bg-surface-muted text-muted-foreground',
};

export function DashboardRail({ alerts, actions, absent }: {
  alerts: Alert[];
  actions: QuickAction[];
  absent: string[];
}) {
  return (
    <div className="space-y-5">
      {alerts.length > 0 && (
        <Panel title="Needs attention">
          <ul className="space-y-2 px-4 pb-4">
            {alerts.map((alert, i) => (
              <li key={i} className={cn('flex gap-2.5 rounded-md px-3 py-2.5 text-sm', TONE[alert.tone])}>
                {alert.tone === 'calm'
                  ? <Info className="mt-0.5 size-4 shrink-0" aria-hidden />
                  : <CircleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />}
                <span>{alert.text}</span>
              </li>
            ))}
          </ul>
        </Panel>
      )}
      <Panel title="Quick actions" subtitle="Only what your role can do.">
        <ul className="px-2 pb-2">
          {actions.map((action) => (
            <li key={action.to}>
              <Link
                to={action.to}
                className="flex items-center gap-3 rounded-md px-2 py-2 transition-colors hover:bg-hover active:bg-selected active:duration-0"
              >
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium">{action.label}</span>
                  <span className="block text-xs text-muted-foreground">{action.description}</span>
                </span>
                <ChevronRight className="size-4 shrink-0 text-subtle-foreground" aria-hidden />
              </Link>
            </li>
          ))}
        </ul>
      </Panel>
      {absent.length > 0 && (
        <Panel title="Not here yet">
          <div className="px-4 pb-4 text-xs text-muted-foreground">
            <p>Left out on purpose — the platform has no concept behind them yet:</p>
            <ul className="mt-1.5 list-disc space-y-1 pl-4">
              {absent.map((item) => <li key={item}>{item}</li>)}
            </ul>
          </div>
        </Panel>
      )}
    </div>
  );
}
