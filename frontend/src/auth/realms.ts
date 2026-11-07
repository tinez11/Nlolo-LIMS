/**
 * The four workspaces of the console, and the routes they own.
 *
 * Since 2026-11-06 (universal login) there is ONE Keycloak realm, `lifeplatform`, for everyone. A workspace is a realm
 * role on the account (`staff`, `agent`, `customer`, `regulator`), and the URL prefix says which workspace a page is in
 * -- which is also what the console tells the backend, as `X-Workspace`, on every request. The type keeps its old name,
 * `Realm`, because the four prefixes and every screen keyed by them are unchanged.
 */

export const REALMS = ['staff', 'agents', 'customers', 'regulators'] as const;
export type Realm = (typeof REALMS)[number];

export type WorkspaceRole = 'staff' | 'agent' | 'customer' | 'regulator';

export interface RealmConfig {
  realm: Realm;
  /** URL segment and route prefix. */
  slug: string;
  label: string;
  description: string;
  /** Synthetic authority the backend grants for a request in this workspace. */
  realmAuthority: string;
  /** The Keycloak realm role that opens this workspace, and the X-Workspace value naming it. */
  workspaceRole: WorkspaceRole;
}

export const REALM_CONFIG: Record<Realm, RealmConfig> = {
  staff: {
    realm: 'staff',
    slug: 'staff',
    label: 'Staff',
    description: 'Underwriting, claims, finance and administration',
    realmAuthority: 'REALM_STAFF',
    workspaceRole: 'staff',
  },
  agents: {
    realm: 'agents',
    slug: 'agents',
    // The user's names for the workspaces (2026-11-07): Sales and Customer. The insurance terms inside the screens
    // ("Policyholder" on a policy, the staff Agents pages) are unchanged -- they name a party's role, not a console.
    label: 'Sales',
    description: 'Your book of business and commission statements',
    realmAuthority: 'REALM_AGENTS',
    workspaceRole: 'agent',
  },
  customers: {
    realm: 'customers',
    slug: 'customers',
    label: 'Customer',
    description: 'Your policies, claims and payments',
    realmAuthority: 'REALM_CUSTOMERS',
    workspaceRole: 'customer',
  },
  regulators: {
    realm: 'regulators',
    slug: 'regulators',
    label: 'Regulator',
    description: 'Regulatory returns',
    realmAuthority: 'REALM_REGULATORS',
    workspaceRole: 'regulator',
  },
};

const KEYCLOAK_BASE = import.meta.env.VITE_KEYCLOAK_BASE_URL ?? 'http://localhost:8081';

/** `lifeplatform-spa` -- the PUBLIC PKCE client. `lifeplatform-app` is confidential
 *  and cannot be used from a browser at all. */
export const CLIENT_ID = import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'lifeplatform-spa';

/** The one realm (2026-11-06). */
export const ISSUER = `${KEYCLOAK_BASE}/realms/lifeplatform`;

export function isRealm(value: string | undefined): value is Realm {
  return value !== undefined && (REALMS as readonly string[]).includes(value);
}

/** The workspaces an account holds, in console order. Job roles (ADMIN, …) are not workspaces. */
export function workspacesOf(roles: readonly string[]): Realm[] {
  return REALMS.filter((realm) => roles.includes(REALM_CONFIG[realm].workspaceRole));
}

/** The workspace a path is in: its first segment, when that is a workspace's slug. */
export function realmForPath(pathname: string): Realm | null {
  const first = pathname.split('/')[1];
  return REALMS.find((realm) => REALM_CONFIG[realm].slug === first) ?? null;
}

/** The X-Workspace value for a path, or null outside a workspace. */
export function workspaceHeaderFor(pathname: string): WorkspaceRole | null {
  const realm = realmForPath(pathname);
  return realm ? REALM_CONFIG[realm].workspaceRole : null;
}
