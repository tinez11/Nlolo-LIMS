import type { CaseSource } from '@/api/underwriting';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/cn';
import { CASE_SOURCE_LABEL } from './caseSource';

/** The source as a label; a call back is tinted, because it needs a phone call before anything can be decided. */
export function CaseSourceBadge({ source }: { source: CaseSource | null | undefined }) {
  if (!source) return <span className="text-muted-foreground">—</span>;
  return (
    <Badge className={cn(source === 'CALL_BACK' && 'bg-status-warning-bg text-status-warning-fg')}>
      {CASE_SOURCE_LABEL[source]}
    </Badge>
  );
}
