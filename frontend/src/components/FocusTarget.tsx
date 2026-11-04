import { useEffect, useRef, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';

/**
 * A section a quick action can open straight to (2026-11-04): with `?focus=<id>` in the address, it scrolls into view
 * once, so "Surrender" lands on the Value panel rather than the top of the record.
 */
export function FocusTarget({ id, children }: { id: string; children: ReactNode }) {
  const [params] = useSearchParams();
  const ref = useRef<HTMLDivElement>(null);
  const focused = params.get('focus') === id;

  useEffect(() => {
    if (focused) ref.current?.scrollIntoView({ block: 'start', behavior: 'smooth' });
  }, [focused]);

  return (
    <div ref={ref} id={id} className="scroll-mt-[calc(var(--pagebar-h,4rem)+1rem)]">
      {children}
    </div>
  );
}
