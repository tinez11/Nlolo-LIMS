import { useEffect, useRef, useState } from 'react';
import { cn } from '@/lib/cn';
import { activeSection } from './sectionSpy';

/**
 * Jump links to the sections of a page that must stay one scroll.
 *
 * The claim page's answer to the policy page's tabs: an assessor needs the event details, the
 * evidence and their own findings on one surface, so nothing is hidden -- the bar only makes
 * the fifth section one click away instead of a long scroll. Targets are `Panel id`s, which
 * carry the scroll margin that clears this bar and the page bar above it.
 *
 * It publishes its own height as `--sectionbar-h`, exactly as `PageHeader` publishes
 * `--pagebar-h` and for the same reason: two things now stick to the top of the viewport, and
 * everything below them -- the panels' scroll margin, the pinned record rail -- has to stop
 * under BOTH. Until this bar had a call site, `Panel` guessed 3.5rem for it, which is 22px
 * more than it measures; nothing noticed, because nothing rendered it.
 */
export function SectionNav({
  sections,
  label,
}: {
  sections: { id: string; label: string }[];
  label: string;
}) {
  const bar = useRef<HTMLElement>(null);
  const [current, setCurrent] = useState<string | null>(sections[0]?.id ?? null);

  useEffect(() => {
    const node = bar.current;
    if (!node) return;
    const publish = () =>
      document.documentElement.style.setProperty('--sectionbar-h', `${node.offsetHeight}px`);
    publish();
    const observer = new ResizeObserver(publish);
    observer.observe(node);
    return () => {
      observer.disconnect();
      document.documentElement.style.removeProperty('--sectionbar-h');
    };
  }, []);

  // `sections` is rebuilt on every render of the caller, so this effect depends on the ids it
  // contains rather than on the array identity -- otherwise it would tear down and re-attach
  // its scroll listener on every keystroke in a form three panels down.
  const ids = sections.map((section) => section.id).join(' ');

  useEffect(() => {
    let frame = 0;
    const measure = () => {
      frame = 0;
      const tops = ids
        .split(' ')
        .map((id) => ({ id, node: document.getElementById(id) }))
        .filter((entry): entry is { id: string; node: HTMLElement } => entry.node !== null)
        .map((entry) => ({
          id: entry.id,
          top: entry.node.getBoundingClientRect().top + window.scrollY,
        }));
      // Nothing measurable yet -- this runs before the panels below have been laid out, and on
      // any frame where a section's element is missing. Keep whatever is marked rather than
      // clearing it: a bar that marks the first section is right far more often than a bar
      // that marks none, and a blank bar is what a reader reads as "this control is broken".
      if (tops.length === 0) return;
      const offset = (bar.current?.offsetHeight ?? 0) + pageBarHeight();
      const atBottom = window.scrollY + window.innerHeight >= document.body.scrollHeight - 2;
      setCurrent(activeSection(tops, window.scrollY, offset, atBottom));
    };
    // Coalesced to one measurement per frame: scroll fires far more often than the answer can
    // change, and measuring reads layout, which forces a reflow every time it runs.
    const onScroll = () => {
      if (frame === 0) frame = requestAnimationFrame(measure);
    };
    measure();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll, { passive: true });
    return () => {
      if (frame !== 0) cancelAnimationFrame(frame);
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);
    };
  }, [ids]);

  return (
    <nav
      ref={bar}
      aria-label={label}
      // Marked so `DetailLayout` can observe this element directly, the way it observes the
      // page bar: a CSS custom property is only reactive to CSS, and the rail has to
      // re-measure when this bar appears.
      data-sectionbar
      className="sticky top-[var(--pagebar-h,0px)] z-10 flex gap-1 overflow-x-auto overflow-y-hidden border-b border-border bg-background px-6 py-1.5"
    >
      {sections.map((section) => (
        <a
          key={section.id}
          href={`#${section.id}`}
          // aria-current, not aria-selected: these are links to places on this page, not the
          // options of a widget. A screen reader says "current page" on the one you are in,
          // which is what the weight change says to everybody else.
          aria-current={section.id === current ? true : undefined}
          className={cn(
            'shrink-0 rounded-md px-2.5 py-1 text-sm transition-colors hover:bg-hover hover:text-foreground active:bg-selected active:duration-0 pointer-coarse:py-2.5',
            section.id === current
              ? 'bg-control font-medium text-foreground'
              : 'text-muted-foreground',
          )}
        >
          {section.label}
        </a>
      ))}
    </nav>
  );
}

/** The page bar's height, read from the custom property `PageHeader` publishes. */
function pageBarHeight(): number {
  return (
    Number.parseFloat(
      getComputedStyle(document.documentElement).getPropertyValue('--pagebar-h'),
    ) || 0
  );
}
