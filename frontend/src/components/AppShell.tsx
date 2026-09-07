import { LogOut, Moon, Sun } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { avatarHue, displayName, initials, readIdentity } from '@/auth/claims';
import { REALM_CONFIG, type Realm } from '@/auth/realms';
import { cn } from '@/lib/cn';
import { currentTheme, toggleTheme, type Theme } from '@/lib/theme';
import { useNavBadges } from '@/navBadges';
import { navFor } from '@/screens';
import { Button } from './ui/button';

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

  const groups = navFor(realm, identity);
  const badges = useNavBadges(realm, identity);

  return (
    <div className="flex h-full">
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

      <aside className="flex w-56 shrink-0 flex-col border-r border-border bg-surface-muted">
        <div className="px-4 py-4">
          <p className="text-sm font-semibold tracking-tight">Life Platform</p>
          <p className="text-xs text-muted-foreground">{config.label} console</p>
        </div>

        <nav className="flex-1 space-y-5 px-2 py-2" aria-label="Main">
          {groups.map((group) => (
            <div key={group.label}>
              <p className="px-2 pb-1.5 text-[11px] font-medium tracking-wide text-subtle-foreground uppercase">
                {group.label}
              </p>
              <ul className="space-y-0.5">
                {group.items.map((item) => (
                  <li key={item.to}>
                    <NavLink
                      to={item.to}
                      className={({ isActive }) =>
                        cn(
                          'flex items-center gap-2.5 rounded-md px-2 py-1.5 text-sm transition-colors',
                          isActive
                            ? 'bg-selected font-medium text-foreground'
                            : 'text-muted-foreground hover:bg-hover hover:text-foreground',
                        )
                      }
                    >
                      <item.icon className="size-4 shrink-0" aria-hidden />
                      <span className="min-w-0 flex-1 truncate">{item.label}</span>
                      {item.badge && badges[item.badge] && (
                        <span
                          className="shrink-0 rounded-full bg-selected px-1.5 text-[11px] font-medium text-muted-foreground tabular-nums"
                          // The count alone reads as "3 claims", which is not what
                          // it means. Both the tooltip and the screen-reader text
                          // say what was counted.
                          title={badges[item.badge]!.title}
                        >
                          {badges[item.badge]!.count}
                          <span className="sr-only"> — {badges[item.badge]!.title}</span>
                        </span>
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

      {/* tabIndex={-1} so the skip link's target can actually take focus --
          without it the browser scrolls but leaves focus behind in the sidebar,
          and the next Tab carries on through the nav as if nothing happened. */}
      <main id="main" tabIndex={-1} className="min-w-0 flex-1 overflow-y-auto focus:outline-none">
        {children}
      </main>
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

  return (
    <div className="border-t border-border px-3 py-3">
      <div className="flex items-center gap-2.5">
        <span
          className="grid size-7 shrink-0 place-items-center rounded-full text-[11px] font-semibold"
          style={{
            // Deterministic hue so the same person is always the same colour. Parties
            // on this platform have no photos, so the initials fallback IS the avatar.
            backgroundColor: `oklch(0.92 0.05 ${avatarHue(seed)})`,
            color: `oklch(0.35 0.09 ${avatarHue(seed)})`,
          }}
          aria-hidden
        >
          {initials(seed)}
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-xs font-medium">{name}</p>
          <p className="truncate text-[11px] text-muted-foreground">
            {identity.roles.length > 0 ? identity.roles.join(', ') : 'No roles'}
          </p>
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
