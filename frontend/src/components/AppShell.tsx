import { LogOut, Menu, Moon, Search, Sun, X } from 'lucide-react';
import { Suspense, useEffect, useRef, useState, type ReactNode } from 'react';
import { NavLink, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { avatarHue, avatarInitials, displayName, readIdentity } from '@/auth/claims';
import { REALM_CONFIG, type Realm } from '@/auth/realms';
import { cn } from '@/lib/cn';
import { humanizeStatus } from '@/lib/status';
import { currentTheme, toggleTheme, type Theme } from '@/lib/theme';
import { useNavBadges } from '@/navBadges';
import { navFor } from '@/screens';
import { CommandPalette } from './CommandPalette';
import { LoadingBlock } from './states';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Tip } from './ui/tooltip';

/**
 * The console chrome: sidebar, nav, user block.
 *
 * The nav is derived from the screen manifest (`@/screens`), not declared here.
 * Which screens get a sidebar item -- and why most deliberately do not -- is
 * recorded on the manifest entries themselves via their `reach` field.
 */
export function AppShell({ realm, children }: { realm: Realm; children: ReactNode }) {
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const config = REALM_CONFIG[realm];

  const location = useLocation();
  const groups = navFor(realm, identity);
  const badges = useNavBadges(realm, identity);

  // Below `md` the sidebar is an overlay drawer rather than a column: at 240px
  // fixed it would otherwise eat half a phone screen. Desktop is the designed
  // scene (PRODUCT.md), so the drawer is the narrow-width accommodation, not a
  // second layout to maintain -- the same markup, repositioned.
  const [navOpen, setNavOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const openButton = useRef<HTMLButtonElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);

  // Ctrl+K / Cmd+K anywhere in the console. An event handler, never a synchronous setState
  // in an effect body, which this project's lint rule forbids.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setPaletteOpen(true);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  useEffect(() => {
    if (!navOpen) return;
    // Move focus into the drawer on open, and hand it back to the trigger on
    // close. Both halves are needed: without the first, Tab from the hamburger
    // walks into an inert page; without the second, dismissing the drawer drops
    // a keyboard user back at the top of the document with no idea where.
    closeButton.current?.focus();
    // Captured now rather than read in the cleanup: the trigger is the same
    // node throughout this effect's life, but reading a ref after teardown is
    // the bug the lint rule exists to catch, and it is right to insist.
    const trigger = openButton.current;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setNavOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      trigger?.focus();
    };
  }, [navOpen]);

  /*
    `fixed inset-0` rather than `h-full`, and the difference is not cosmetic.
    With a percentage height the shell measured a correct 900px on a 900px
    viewport, yet `documentElement.scrollHeight` reported 1061 -- 161px of
    layout overflow leaking out of `main`, which clips its own content and
    carries its own scrollbar. The page therefore scrolled a second time,
    underneath the first, and scrolling to the bottom of a register lifted the
    whole shell off the viewport floor: the sidebar ended 161px short with a
    band of bare background beneath it, and a second scrollbar sat beside main's
    own. It only bit when the window was shorter than the content -- invisible
    at 1920x1080, plain at 1920x900.

    Clipping did not fix it: `overflow: hidden` on the shell, on `#root`, on
    `body` and on `html` all left the overflow intact. Taking the shell out of
    flow does, because then nothing it contains can size the root scroller.
    Measured after: scrollHeight 900 on a 900px viewport, sidebar flush.
  */
  return (
    <div className="fixed inset-0 flex">
      {/*
        Skip link. Measured at 19 tab stops from page load to the first table
        row, on every single navigation, because the whole sidebar sits ahead of
        the content in the tab order. WCAG 2.4.1 is Level A.

        Visually hidden until focused rather than always on screen: `sr-only`
        alone would make it unreachable for a sighted keyboard user, who needs to
        SEE where the first Tab went. It is the first focusable thing in the DOM,
        which is the only position that helps.
      */}
      <a
        href="#main"
        className="sr-only rounded-md bg-accent px-3 py-2 text-sm font-medium text-accent-foreground focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-50"
      >
        Skip to content
      </a>

      {/* Dismiss layer. Pointer only -- Escape and the close button carry the
          keyboard path, so this is aria-hidden rather than a fake button. */}
      {navOpen && (
        <div
          className="fixed inset-0 z-30 bg-foreground/25 md:hidden"
          onClick={() => setNavOpen(false)}
          aria-hidden
        />
      )}

      <aside
        id="sidebar"
        className={cn(
          'fixed inset-y-0 left-0 z-40 flex w-60 shrink-0 flex-col border-r border-border bg-surface-muted transition-[transform,visibility] duration-200 ease-out',
          // From `md` up it is an ordinary flex column again and the transform
          // is neutralised, so the desktop scene keeps exactly its old layout.
          'md:visible md:static md:translate-x-0',
          // `invisible` rather than transform alone: an off-screen drawer is
          // still in the tab order, so a phone user would otherwise Tab through
          // twenty-two hidden destinations before reaching the page. Visibility
          // is in the transition so it holds until the slide-out finishes, and
          // unlike `inert` it can be lifted at a breakpoint.
          navOpen ? 'visible translate-x-0' : 'invisible -translate-x-full',
        )}
      >
        <div className="flex shrink-0 items-center justify-between gap-2 px-4 py-4">
          <div className="flex min-w-0 items-center gap-2.5">
            {/* A monogram in ink, not a logo: several insurers are tenants of this one
                console, so the mark belongs to the platform and can carry no tenant's
                colour. */}
            <span
              className="grid size-8 shrink-0 place-items-center rounded-md bg-accent text-xs font-semibold text-accent-foreground"
              aria-hidden
            >
              LP
            </span>
            <div className="min-w-0">
              <p className="truncate text-sm font-semibold">Life Platform</p>
              <p className="truncate text-xs text-muted-foreground">{config.label} console</p>
            </div>
          </div>
          <Button
            ref={closeButton}
            size="icon"
            variant="ghost"
            className="-mr-1 md:hidden"
            aria-label="Close navigation"
            onClick={() => setNavOpen(false)}
          >
            <X />
          </Button>
        </div>

        <div className="shrink-0 px-2 pb-2">
          <button
            type="button"
            onClick={() => setPaletteOpen(true)}
            // No aria-label: the visible words ARE the name. An aria-label of "Go to a
            // screen" replaced them, and a voice-control user saying the words they can
            // see would have matched nothing (WCAG 2.5.3, Label in Name). The shortcut
            // hint is aria-hidden so the name stays the sentence, not "Go to… Ctrl K".
            className="flex w-full items-center gap-2 rounded-md bg-control px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-control-hover hover:text-foreground pointer-coarse:min-h-11"
          >
            <Search className="size-4 shrink-0" aria-hidden />
            <span className="flex-1 text-left">Go to…</span>
            <kbd className="font-sans text-xs" aria-hidden>
              Ctrl K
            </kbd>
          </button>
        </div>

        {/*
          `min-h-0` is the load-bearing half of this pair, and it is not
          decoration: a flex child defaults to `min-height: auto`, which refuses
          to shrink below its content. A full-access staff user carries eight
          nav groups and twenty-two items -- taller than a laptop viewport -- so
          without it the nav cannot shrink, the aside is forced past the bottom
          of the screen, and the UserBlock below (theme toggle and SIGN OUT)
          goes off-screen with no way to reach it. The whole page then scrolls
          instead of `main`, which is what dragged the sidebar along with it.

          `overscroll-contain` stops a scroll that reaches the end of the nav
          from chaining into the content column behind it.
        */}
        <nav
          className="min-h-0 flex-1 space-y-5 overflow-y-auto overscroll-contain px-2 py-2"
          aria-label="Main"
          // Tapping a destination dismisses the drawer. Delegated from the nav
          // rather than wired onto every NavLink, and a no-op on desktop where
          // navOpen is never true.
          onClick={() => setNavOpen(false)}
          style={{
            /*
              Scroll shadows, pure CSS. The first two gradients are painted
              `local` so they scroll with the content; the last two are `scroll`
              so they stay pinned to the visible edges. At the top of the list
              the local cover sits exactly over the pinned shadow and hides it,
              and it slides away as you scroll -- so an edge shadow appears only
              when there is genuinely more nav in that direction, and vanishes
              at either end. No JS and no state, which is what keeps it honest:
              it cannot get out of step with the real scroll position.

              This is what the clipped row at the seam with the user block was
              missing -- the overlay scrollbar only paints while scrolling, so
              until now nothing said the list continued.
            */
            backgroundImage: [
              'linear-gradient(to bottom, var(--color-surface-muted), transparent)',
              'linear-gradient(to top, var(--color-surface-muted), transparent)',
              'linear-gradient(to bottom, var(--color-border), transparent)',
              'linear-gradient(to top, var(--color-border), transparent)',
            ].join(', '),
            backgroundPosition: 'top, bottom, top, bottom',
            backgroundSize: '100% 20px, 100% 20px, 100% 8px, 100% 8px',
            backgroundRepeat: 'no-repeat',
            backgroundAttachment: 'local, local, scroll, scroll',
          }}
        >
          {groups.map((group) => (
            <div key={group.label}>
              <p className="px-2 pb-1.5 text-eyebrow text-subtle-foreground uppercase">
                {group.label}
              </p>
              <ul className="space-y-0.5">
                {group.items.map((item) => (
                  <li key={item.to}>
                    <NavLink
                      to={item.to}
                      className={({ isActive }) =>
                        cn(
                          // Espresso's nav rhythm, one step tighter horizontally: at its
                          // full 12px the longest label on this console ("Corporate/Group",
                          // beside a count) truncated to "Corporate/Gro...", and a nav item
                          // that cannot say its own name is a worse trade than 2px.
                          'flex items-center gap-2.5 rounded-md px-2.5 py-2 text-sm transition-colors pointer-coarse:min-h-11',
                          // Raised AND weighted. Espresso marks the active item with a
                          // white pill on its grey sidebar, which measures 1.06:1 -- the
                          // shadow is carrying it alone. Weight is the second signal, and
                          // the one that survives a dim screen or a colour-blind reader.
                          // `dark:bg-selected` because the light and dark grounds run in
                          // opposite directions: in light, Paper above Quiet Paper is a
                          // lift; in dark, --surface (0.185) sits barely above the
                          // sidebar's --surface-muted (0.165) and reads as LESS separated
                          // than the --selected it replaced. Dark keeps the brighter tone;
                          // both keep the weight, which is the signal that survives either.
                          isActive
                            ? 'bg-surface font-medium text-foreground shadow-raise dark:bg-selected'
                            : 'text-muted-foreground hover:bg-hover hover:text-foreground active:bg-selected active:duration-0',
                        )
                      }
                    >
                      <item.icon className="size-4 shrink-0" aria-hidden />
                      <span className="min-w-0 flex-1 truncate">{item.label}</span>
                      {item.badge && badges[item.badge] && (
                        // The count alone reads as "3 claims", which is not what it means. Both
                        // the tip and the screen-reader text say what was counted; the link it
                        // sits in already carries the text for a keyboard.
                        <Tip content={badges[item.badge]!.title}>
                          <span className="shrink-0">
                            <Badge className="px-1.5 py-0 tabular-nums">
                              {badges[item.badge]!.count}
                              <span className="sr-only"> — {badges[item.badge]!.title}</span>
                            </Badge>
                          </span>
                        </Tip>
                      )}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>

        <UserBlock identity={identity} />
      </aside>

      <CommandPalette
        realm={realm}
        groups={groups}
        open={paletteOpen}
        onOpenChange={setPaletteOpen}
      />

      {/* `inert` while the drawer is open so Tab cannot walk out of the overlay
          into the page behind it. Cheaper and harder to get wrong than a
          hand-rolled focus trap, and it also hides the content from assistive
          tech, which a visual backdrop alone does not. */}
      <div className="flex min-w-0 flex-1 flex-col" inert={navOpen || undefined}>
        <div className="flex shrink-0 items-center gap-2 border-b border-border px-3 py-2 md:hidden">
          <Button
            ref={openButton}
            size="icon"
            variant="ghost"
            aria-label="Open navigation"
            aria-expanded={navOpen}
            aria-controls="sidebar"
            onClick={() => setNavOpen(true)}
          >
            <Menu />
          </Button>
          <p className="truncate text-sm font-semibold">{config.label} console</p>
        </div>

        {/* tabIndex={-1} so the skip link's target can actually take focus --
            without it the browser scrolls but leaves focus behind in the sidebar,
            and the next Tab carries on through the nav as if nothing happened. */}
        <main
          id="main"
          tabIndex={-1}
          className="min-h-0 min-w-0 flex-1 overflow-y-auto focus:outline-none"
        >
          {/*
            Every screen is a lazy chunk (see `@/lazyPages`), so a first visit to one
            suspends while it downloads. LoadingBlock is the console's own `role="status"`
            surface, which is what the rest of the app shows while it waits.

            KEYED ON THE PATH, and that is the whole of it: react-router 7 wraps every
            location update in `startTransition`, and a Suspense boundary that ALREADY has
            committed content does not swap to its fallback inside a transition -- React
            holds the old screen instead. Without the key, the fallback would paint exactly
            once, on the first screen after sign-in, and every later navigation to an
            undownloaded chunk would leave the previous screen on display under a URL that
            had already changed. The key makes each path a fresh, contentless boundary,
            which is allowed to show its fallback.
          */}
          <Suspense key={location.pathname} fallback={<LoadingBlock />}>
            {children}
          </Suspense>
        </main>
      </div>
    </div>
  );
}

function UserBlock({ identity }: { identity: ReturnType<typeof readIdentity> }) {
  const auth = useAuth();
  const navigate = useNavigate();
  // The inline script in index.html has already applied the class before first
  // paint, so reading it during initialisation is correct -- no effect needed.
  const [theme, setTheme] = useState<Theme>(() => currentTheme());
  const name = displayName(identity);
  const seed = identity.preferredUsername ?? name;
  // The platform's own roles only: they are SCREAMING_SNAKE, while Keycloak's built-ins that can
  // ride the same claim (`offline_access`, `default-roles-…`) are not, and are nothing a person holds.
  const roleLine = identity.roles
    .filter((role) => /^[A-Z][A-Z_]*$/.test(role))
    .map(humanizeStatus)
    .join(' · ');

  return (
    // No rule above it (2026-10-09): the nav's own scroll shadow marks the edge when there is more
    // nav above, and nothing needs marking when there is not.
    <div className="shrink-0 px-3 py-3">
      <div className="flex items-center gap-2.5">
        <span
          className="grid size-7 shrink-0 place-items-center rounded-full text-xs font-semibold"
          style={{
            // Deterministic hue so the same person is always the same colour. Parties
            // on this platform have no photos, so the initials fallback IS the avatar.
            backgroundColor: `oklch(0.92 0.05 ${avatarHue(seed)})`,
            color: `oklch(0.35 0.09 ${avatarHue(seed)})`,
          }}
          aria-hidden
        >
          {avatarInitials(identity)}
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-xs font-medium">{name}</p>
          {/* Roles as words, not enums (2026-10-09): "UNDERWRITER, FINANCE..." shouted, and at 240px
              the full set still may not fit, so the whole of it is the title. */}
          {roleLine ? (
            <Tip content={roleLine}>
              <p tabIndex={0} className="truncate rounded-sm text-xs text-muted-foreground">
                {roleLine}
              </p>
            </Tip>
          ) : (
            <p className="truncate text-xs text-muted-foreground">No roles</p>
          )}
        </div>
      </div>

      <div className="mt-2.5 flex items-center gap-1">
        <Button
          size="sm"
          variant="ghost"
          className="flex-1 justify-start"
          onClick={() => setTheme(toggleTheme())}
        >
          {theme === 'dark' ? <Sun /> : <Moon />}
          {theme === 'dark' ? 'Light' : 'Dark'}
        </Button>
        <Button
          size="icon"
          variant="ghost"
          aria-label="Sign out"
          onClick={() => {
            // End the Keycloak session too, not just the local one -- otherwise the
            // next visit silently signs straight back in.
            void auth.signoutRedirect().catch(() => navigate('/'));
          }}
        >
          <LogOut />
        </Button>
      </div>
    </div>
  );
}
