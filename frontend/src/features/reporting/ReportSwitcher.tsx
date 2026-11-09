import * as Popover from '@radix-ui/react-popover';
import { ChevronDown, LayoutList } from 'lucide-react';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { POPOVER_MOTION } from '@/components/ui/motion';
import { cn } from '@/lib/cn';
import { ReportList } from './ReportList';
import type { ReportArea } from './reportAreas';

/**
 * Every report, one click from the report you are reading (2026-11-09, the user: "the whole layout is wrong"). The
 * list the user chose -- areas closed, the open report's area open, a search -- now opens on demand instead of standing
 * beside the report, so a report has the whole width for its table.
 */
export function ReportSwitcher({ areas, currentKey }: { areas: ReportArea[]; currentKey: string }) {
  const [open, setOpen] = useState(false);
  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <Button size="sm" variant="outline" aria-label="All reports">
          <LayoutList aria-hidden />
          All reports
          <ChevronDown aria-hidden />
        </Button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          align="start"
          sideOffset={6}
          collisionPadding={12}
          onClick={(e) => {
            // Choosing a report closes the list; opening an area or typing a search does not.
            if ((e.target as HTMLElement).closest('a')) setOpen(false);
          }}
          className={cn(
            'z-50 max-h-[min(32rem,var(--radix-popover-content-available-height))] w-80 overflow-y-auto rounded-xl',
            'border border-border bg-surface p-3 shadow-lg',
            POPOVER_MOTION,
          )}
        >
          <ReportList areas={areas} currentKey={currentKey} />
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
