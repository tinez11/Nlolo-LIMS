import {
  BookText,
  ClipboardCheck,
  FileText,
  LogOut,
  Moon,
  Package,
  Receipt,
  ScrollText,
  Shield,
  Sun,
  Users,
  Wallet,
} from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { avatarHue, canSeeFinance, displayName, initials, readIdentity } from '@/auth/claims';
import { REALM_CONFIG, type Realm } from '@/auth/realms';
import { cn } from '@/lib/cn';
import { currentTheme, toggleTheme, type Theme } from '@/lib/theme';
import { Button } from './ui/button';

/**
 * Nav definition for the staff realm.
 *
 * `implemented` gates rendering. Every entry here has a real list endpoint behind
 * it -- entities that are fetch-by-ID only (parties, payments, payout batches,
 * documents) deliberately get NO nav item, because an item that leads to a
 * "paste an ID" screen reads as broken software. They are reached by drilling
 * in from a policy or claim.
 *
 * Underwriting and Agents are the two exceptions, and deliberately not
 * "paste an ID" screens: `POST /underwriting/cases` and `POST /agents` are
 * the only entry points onto those domains that exist server-side (neither
 * has a list/search endpoint), so each nav item goes straight to the one
 * real, working action -- opening a case, onboarding an agent -- rather than
 * a lookup box. Underwriting's own case id is never re-surfaced anywhere else
 * on this platform (confirmed against the actual `PolicyResponseDto` wire
 * type, which omits `underwritingCaseId` entirely despite the internal
 * same-named `PolicyView` record carrying it), so its detail page is explicit
 * that the id it hands back is the only way to return. An agent is different:
 * `PolicyView.agentOfRecordId` DOES round-trip through `GET /policies` for
 * real, so an agent is also reachable by drilling in from a policy that names
 * one -- Agents' nav entry is just the first way in, not the only one.
 *
 * The unimplemented entries are listed rather than deleted so the intended shape is
 * visible, but they are filtered out below: shipping a link to an empty page is the
 * same dead end by another route.
 */
interface NavItem {
  to: string;
  label: string;
  icon: typeof FileText;
  implemented: boolean;
}

interface NavGroup {
  label: string;
  items: NavItem[];
  /** Undefined means always visible. */
  requires?: (identity: ReturnType<typeof readIdentity>) => boolean;
}

const STAFF_NAV: NavGroup[] = [
  {
    label: 'Operations',
    items: [
      { to: 'policies', label: 'Policies', icon: FileText, implemented: true },
      { to: 'claims', label: 'Claims', icon: ScrollText, implemented: true },
      { to: 'products', label: 'Products', icon: Package, implemented: true },
      { to: 'underwriting/new', label: 'Underwriting', icon: ClipboardCheck, implemented: true },
    ],
  },
  {
    label: 'Finance',
    // Mirrors the backend expression on every finance endpoint:
    // hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))
    requires: canSeeFinance,
    items: [
      { to: 'gl-postings', label: 'GL postings', icon: BookText, implemented: false },
      { to: 'chart-of-accounts', label: 'Chart of accounts', icon: Wallet, implemented: false },
      { to: 'treaties', label: 'Treaties', icon: Shield, implemented: false },
      { to: 'regulatory-returns', label: 'Regulatory returns', icon: Receipt, implemented: false },
      { to: 'agents/new', label: 'Agents', icon: Users, implemented: true },
    ],
  },
];

export function AppShell({ realm, children }: { realm: Realm; children: ReactNode }) {
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const config = REALM_CONFIG[realm];

  const groups = STAFF_NAV.map((group) => ({
    ...group,
    items: group.items.filter((item) => item.implemented),
  })).filter((group) => group.items.length > 0 && (group.requires?.(identity) ?? true));

  return (
    <div className="flex h-full">
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
                      {item.label}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>

        <UserBlock identity={identity} />
      </aside>

      <main className="min-w-0 flex-1 overflow-y-auto">{children}</main>
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

/** Page header used by every screen, so titles and actions align across the console. */
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
        {description && (
          <p className="mt-1 text-sm text-muted-foreground">{description}</p>
        )}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}
