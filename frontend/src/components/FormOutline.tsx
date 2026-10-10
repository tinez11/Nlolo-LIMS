import { useEffect, useState } from 'react';
import { cn } from '@/lib/cn';

interface Section {
  id: string;
  label: string;
}

/**
 * A map of a long form, in the column beside it (2026-10-09, review C2).
 *
 * The product version form is fifteen hundred lines of sections, and which sections it has depends
 * on the product category -- an annuity grows annuity forms, a savings product grows a value basis,
 * a funeral product drops the base rates. A fixed list would be wrong for most products, so this
 * READS the form: every section wrapper marked `data-outline="Label"` inside `#containerId`, kept
 * current by a MutationObserver as sections come and go.
 *
 * The one you are in is marked as you scroll; choosing one scrolls it to the top under the sticky
 * page bar. Buttons rather than `#hash` links: the sections have no stable ids of their own, and a
 * hash would also land in the URL and in Back.
 */
export function FormOutline({ containerId, label }: { containerId: string; label: string }) {
  const [sections, setSections] = useState<Section[]>([]);
  const [current, setCurrent] = useState<string | null>(null);

  useEffect(() => {
    const container = document.getElementById(containerId);
    if (!container) return;

    const read = () => {
      const found = [...container.querySelectorAll<HTMLElement>('[data-outline]')].map((el, index) => {
        if (!el.dataset.outlineId) el.dataset.outlineId = `${containerId}-section-${index}`;
        return { id: el.dataset.outlineId, label: el.dataset.outline ?? '' };
      });
      setSections((previous) =>
        previous.length === found.length && previous.every((s, i) => s.id === found[i]?.id && s.label === found[i]?.label)
          ? previous
          : found,
      );
    };

    const spy = () => {
      const offset =
        (Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--pagebar-h')) || 0) + 24;
      let active: string | null = null;
      for (const el of container.querySelectorAll<HTMLElement>('[data-outline]')) {
        if (el.getBoundingClientRect().top - offset <= 0) active = el.dataset.outlineId ?? null;
      }
      setCurrent(active ?? container.querySelector<HTMLElement>('[data-outline]')?.dataset.outlineId ?? null);
    };

    const frame = requestAnimationFrame(() => {
      read();
      spy();
    });
    const observer = new MutationObserver(() => {
      read();
      spy();
    });
    observer.observe(container, { childList: true, subtree: true });
    const main = document.getElementById('main') ?? window;
    main.addEventListener('scroll', spy, { passive: true });
    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
      main.removeEventListener('scroll', spy);
    };
  }, [containerId]);

  if (sections.length < 2) return null;

  return (
    <nav aria-label={label} className="text-sm">
      <p className="mb-2 text-eyebrow text-subtle-foreground uppercase">On this form</p>
      <ul className="space-y-0.5 border-l border-border">
        {sections.map((section) => (
          <li key={section.id}>
            <button
              type="button"
              aria-current={section.id === current ? 'true' : undefined}
              onClick={() => {
                const target = document.querySelector<HTMLElement>(`[data-outline-id="${section.id}"]`);
                // Smooth unless the person asked for less motion, which CSS cannot overrule from here.
                const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
                target?.scrollIntoView({ block: 'start', behavior: reduce ? 'auto' : 'smooth' });
                setCurrent(section.id);
              }}
              className={cn(
                '-ml-px block w-full border-l-2 py-1 pl-3 text-left transition-colors hover:text-foreground active:duration-0',
                section.id === current
                  ? 'border-foreground font-medium text-foreground'
                  : 'border-transparent text-muted-foreground',
              )}
            >
              {section.label}
            </button>
          </li>
        ))}
      </ul>
    </nav>
  );
}
