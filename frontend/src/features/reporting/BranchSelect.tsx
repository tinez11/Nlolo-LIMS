import { useId } from 'react';
import { cn } from '@/lib/cn';
import { ALL_BRANCHES, type BranchOption } from './branchChoice';

/**
 * The branch choice (2026-11-23, branch views): "All branches", then the register's branches by name, head office
 * first, your own marked. The same native select and label as every report filter (ReportFilterBar's SELECT): a label
 * BESIDE the select, never wrapping it, because a wrapping label takes its accessible name from every option inside.
 *
 * @param value a branch code or {@link ALL_BRANCHES}
 */
export function ReportBranchSelect({ value, options, onChange, layout = 'stacked', className }: {
  value: string;
  options: readonly BranchOption[];
  onChange: (branch: string) => void;
  /** stacked: label above (a report's filter row); inline: label beside (the dashboard's period row) */
  layout?: 'inline' | 'stacked';
  className?: string;
}) {
  const id = useId();
  // While the register loads, the chosen code is still offered, so the select shows it rather than falling back.
  const known = value === ALL_BRANCHES || options.some((o) => o.code === value);
  return (
    <div className={cn('text-xs', layout === 'inline' ? 'flex items-center gap-2' : 'flex flex-col gap-1', className)}>
      <label htmlFor={id} className={cn(layout === 'inline' && 'text-muted-foreground')}>
        Branch
      </label>
      <select
        id={id}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="h-8 rounded-md border border-border-strong bg-surface px-2 text-xs"
      >
        <option value={ALL_BRANCHES}>All branches</option>
        {!known && <option value={value}>{value}</option>}
        {options.map((o) => (
          <option key={o.code} value={o.code}>
            {o.label}
          </option>
        ))}
      </select>
    </div>
  );
}
