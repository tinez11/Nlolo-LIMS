import type { ReactNode } from 'react';

/**
 * Page header used by every screen, so titles and actions align across the console.
 *
 * Lives here rather than in `AppShell` for an import-cycle reason: `AppShell`
 * builds the sidebar from the screen manifest (`@/screens`), and the manifest
 * imports every page component. If pages kept importing their header from
 * `AppShell`, that would close the cycle pages -> AppShell -> screens -> pages,
 * and the manifest constructs its elements at module scope -- so a badly-resolved
 * cycle would surface as an undefined component at first render rather than as a
 * build error.
 */
export function PageHeader({
  title,
  description,
  actions,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-4 px-6 pt-6 pb-4">
      <div className="min-w-0">
        <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
        {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}
