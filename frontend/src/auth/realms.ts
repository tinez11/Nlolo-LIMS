/**
 * The four Keycloak realms, and the routes they own.
 *
 * `react-oidc-context`'s AuthProvider takes exactly ONE authority, so the realm has
 * to be known before the provider can be constructed -- i.e. before the user is
 * authenticated. Realm-scoped routes solve that: the URL carries the realm, one
 * provider is mounted per route subtree, and nothing has to guess.
 */

export const REALMS = ['staff', 'agents', 'customers', 'regulators'] as const;
export type Realm = (typeof REALMS)[number];

export interface RealmConfig {
  realm: Realm;
  /** URL segment and route prefix. */
  slug: string;
  label: string;
  description: string;
  /** Synthetic authority the backend adds for every token from this realm. */
  realmAuthority: string;
}

export const REALM_CONFIG: Record<Realm, RealmConfig> = {
  staff: {
    realm: 'staff',
    slug: 'staff',
    label: 'Staff',
    description: 'Underwriting, claims, finance and administration',
    realmAuthority: 'REALM_STAFF',
  },
  agents: {
    realm: 'agents',
    slug: 'agents',
    label: 'Agent',
    description: 'Your book of business and commission statements',
    realmAuthority: 'REALM_AGENTS',
  },
  customers: {
    realm: 'customers',
    slug: 'customers',
    label: 'Policyholder',
    description: 'Your policies, claims and payments',
    realmAuthority: 'REALM_CUSTOMERS',
  },
  regulators: {
    realm: 'regulators',
    slug: 'regulators',
    label: 'Regulator',
    description: 'Regulatory returns',
    realmAuthority: 'REALM_REGULATORS',
  },
};

const KEYCLOAK_BASE = import.meta.env.VITE_KEYCLOAK_BASE_URL ?? 'http://localhost:8081';

/** `lifeplatform-spa` -- the PUBLIC PKCE client. `lifeplatform-app` is confidential
 *  and cannot be used from a browser at all. */
export const CLIENT_ID = import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'lifeplatform-spa';

export function issuerFor(realm: Realm): string {
  return `${KEYCLOAK_BASE}/realms/${realm}`;
}

export function isRealm(value: string | undefined): value is Realm {
  return value !== undefined && (REALMS as readonly string[]).includes(value);
}
