import type { ReturnLineView } from '@/api/types';
import { formatMoney } from '@/lib/money';

/**
 * `ReturnLineView.value` is a `Money` object when the metric is monetary, or
 * a plain integer-valued decimal string otherwise -- never a raw JSON number
 * (`ReturnLineResponseDto`'s own javadoc). Shared between the drawer preview
 * and the full detail page, since both render the identical lines list.
 */
export function ReturnLinesList({ lines }: { lines: ReturnLineView[] }) {
  if (lines.length === 0) {
    return <p className="text-xs text-muted-foreground">No lines.</p>;
  }
  return (
    <ul className="divide-y divide-border rounded-md border border-border">
      {lines.map((line) => (
        <li key={line.returnLineId} className="flex items-center justify-between gap-3 px-3 py-2 text-xs">
          <div className="min-w-0">
            <span className="font-mono text-xs text-muted-foreground">{line.lineCode}</span>
            <span className="ml-2">{line.label}</span>
          </div>
          <span className="shrink-0 font-medium">{renderValue(line.value)}</span>
        </li>
      ))}
    </ul>
  );
}

function renderValue(value: ReturnLineView['value']): string {
  return typeof value === 'string' ? value : formatMoney(value);
}
