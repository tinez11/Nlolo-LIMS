/**
 * Jump links to the sections of a page that must stay one scroll.
 *
 * The claim page's answer to the policy page's tabs: an assessor needs the event details,
 * the evidence and their own findings on one surface, so nothing is hidden -- the bar only
 * makes the fourth section one click away instead of a long scroll. Targets are `Panel id`s,
 * which carry the scroll margin that clears this bar and the page bar above it.
 */
export function SectionNav({
  sections,
  label,
}: {
  sections: { id: string; label: string }[];
  label: string;
}) {
  return (
    <nav
      aria-label={label}
      className="sticky top-[var(--pagebar-h,0px)] z-10 flex gap-1 overflow-x-auto border-b border-border bg-background px-6 py-1.5"
    >
      {sections.map((section) => (
        <a
          key={section.id}
          href={`#${section.id}`}
          className="shrink-0 rounded-md px-2.5 py-1 text-sm text-muted-foreground transition-colors hover:bg-hover hover:text-foreground pointer-coarse:py-2.5"
        >
          {section.label}
        </a>
      ))}
    </nav>
  );
}
