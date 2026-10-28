import { Loader2 } from 'lucide-react';

/**
 * What a person sees between pressing "Log in" and their console (2026-10-28). Signing in passes through three
 * moments -- the page loading, the token arriving, their access being read -- and each used to draw something
 * different: a white page, then a panel's placeholder lines in the top-left corner, then another. This is one screen
 * for all of them, centred and still, where only the line under the mark changes. `index.html` draws the same screen
 * before the app has loaded, so the first frame is this one too.
 *
 * Not a panel's LoadingBlock: that keeps a panel's shape while its content arrives, and here there is no page yet to
 * keep the shape of.
 */
export function SessionSplash({ label, console: consoleName }: { label: string; console?: string }) {
  return (
    <div className="grid h-full place-items-center bg-background px-6">
      <div className="flex flex-col items-center text-center">
        {/* The sidebar's monogram, at the size of a front door: the platform's, not any one insurer's. */}
        <span
          className="grid size-14 place-items-center rounded-2xl bg-accent font-display text-lg font-semibold text-accent-foreground shadow-card"
          aria-hidden
        >
          LP
        </span>
        <p className="mt-4 font-display text-base font-semibold">Life Platform</p>
        {consoleName && <p className="text-sm text-muted-foreground">{consoleName} console</p>}
        <p className="mt-6 flex items-center gap-2 text-sm text-muted-foreground" role="status">
          <Loader2 className="size-4 animate-spin motion-reduce:animate-none" aria-hidden />
          {label}
        </p>
      </div>
    </div>
  );
}
