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
  UserCheck,
  UserPlus,
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
 * it -- entities that are STILL fetch-by-ID only (payments, payout batches,
 * documents) deliberately get NO nav item, because an item that leads to a
 * "paste an ID" screen reads as broken software. They are reached by drilling
 * in from a policy or claim. Parties used to be in that category too, until
 * `GET /parties` closed the gap: a party PENDING KYC with nothing yet
 * referencing it (a fresh registration) was otherwise invisible to staff, so
 * "KYC review" below is a real list, not a lookup box. Underwriting closed the
 * same gap later still (`GET /underwriting/cases`) -- its own case id STILL
 * never round-trips back out through any other endpoint's response
 * (`PolicyResponseDto` omits `underwritingCaseId` despite the internal
 * same-named `PolicyView` record carrying it), so the queue below is the only
 * way back to a case you didn't bookmark, not a supplementary one.
 *
 * Agents is the one remaining exception, and deliberately not a "paste an ID"
 * screen: `POST /agents` is the only entry point onto that domain that exists
 * server-side (no list/search endpoint), so its nav item goes straight to the
 * one real, working action -- onboarding an agent -- rather than a lookup box.
 * An agent IS reachable another way, though: `PolicyView.agentOfRecordId` DOES
 * round-trip through `GET /policies` for real, so an agent is also reachable
 * by drilling in from a policy that names one -- Agents' nav entry is just the
 * first way in, not the only one.
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
      { to: 'underwriting', label: 'Underwriting', icon: ClipboardCheck, implemented: true },
      { to: 'kyc', label: 'KYC review', icon: UserCheck, implemented: true },
    ],
  },
  {
    label: 'Finance',
    // Mirrors the backend expression on every finance endpoint:
    // hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))
    requires: canSeeFinance,
    items: [
      { to: 'gl-postings', label: 'GL postings', icon: BookText, implemented: true },
      { to: 'chart-of-accounts', label: 'Chart of accounts', icon: Wallet, implemented: true },
      { to: 'treaties', label: 'Treaties', icon: Shield, implemented: true },
      { to: 'regulatory-returns', label: 'Regulatory returns', icon: Receipt, implemented: true },
      { to: 'agents/new', label: 'Agents', icon: Users, implemented: true },
    ],
  },
];

/**
 * Nav definition for the agents realm. Policies/claims reuse the exact same
 * `PoliciesPage`/`ClaimsPage`/`PolicyDetailPage`/`ClaimDetailPage` components
 * the staff console mounts -- the scoping to "this agent's own book of
 * business" happens entirely server-side (`PolicyController`/`ClaimController`
 * resolve the caller's hierarchy team via `DistributionApi.resolveAgentTeam`
 * and filter the query itself), so the frontend never needs its own filter
 * UI. The only client-side differences are cosmetic (title/description, and
 * hiding the staff-only "Issue policy"/"New claim" actions).
 */
const AGENTS_NAV: NavGroup[] = [
  {
    label: 'My business',
    items: [
      { to: 'me', label: 'My profile', icon: Users, implemented: true },
      { to: 'policies', label: 'Policies', icon: FileText, implemented: true },
      { to: 'claims', label: 'Claims', icon: ScrollText, implemented: true },
      { to: 'customers/new', label: 'Onboard a customer', icon: UserPlus, implemented: true },
    ],
  },
];

const NAV_BY_REALM: Record<Realm, NavGroup[]> = {
  staff: STAFF_NAV,
  agents: AGENTS_NAV,
  customers: [],
  regulators: [],
};

export function AppShell({ realm, children }: { realm: Realm; children: ReactNode }) {
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const config = REALM_CONFIG[realm];

  const groups = NAV_BY_REALM[realm]
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => item.implemented),
    }))
    .filter((group) => group.items.length > 0 && (group.requires?.(identity) ?? true));

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
