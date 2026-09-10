/**
 * Reading identity out of the access token.
 *
 * The backend's JwtAuthenticationConverter grants `ROLE_REALM_<REALM>` plus
 * `ROLE_<role>` for every entry in `realm_access.roles`, so the same claim the
 * server authorizes on is the one the UI gates nav on. No extra endpoint, no
 * second source of truth that can disagree.
 *
 * Decoding is for DISPLAY AND NAVIGATION ONLY. The token is not verified here and
 * must never be trusted for authorization -- every gate below is a convenience so
 * the user is not shown a screen that will 403. The backend remains the authority.
 */

export interface StaffRoles {
  UNDERWRITER: boolean;
  /**
   * Additive to UNDERWRITER, never a replacement. It lifts exactly one restriction:
   * deciding an underwriting case against the rules engine's recommendation. Reaching the
   * decision endpoint at all still requires UNDERWRITER.
   */
  SENIOR_UNDERWRITER: boolean;
  CLAIMS_ASSESSOR: boolean;
  CLAIMS_MANAGER: boolean;
  FINANCE_OFFICER: boolean;
  CUSTOMER_SERVICE_REP: boolean;
  ADMIN: boolean;
}

export interface TokenIdentity {
  roles: string[];
  /** Present on customers and agents tokens only. */
  partyId: string | null;
  /** Always present, or TenantContextFilter 403s the request. */
  tenantId: string | null;
  preferredUsername: string | null;
  name: string | null;
  email: string | null;
}

const EMPTY: TokenIdentity = {
  roles: [],
  partyId: null,
  tenantId: null,
  preferredUsername: null,
  name: null,
  email: null,
};

function base64UrlDecode(segment: string): string {
  const padded = segment.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded.padEnd(Math.ceil(padded.length / 4) * 4, '='));
  // Handle non-ASCII names correctly rather than mangling them.
  const bytes = Uint8Array.from(binary, (ch) => ch.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

function readString(source: Record<string, unknown>, key: string): string | null {
  const value = source[key];
  return typeof value === 'string' && value.length > 0 ? value : null;
}

/** Decode the identity claims from an access token. Never throws. */
export function readIdentity(accessToken: string | undefined): TokenIdentity {
  if (!accessToken) return EMPTY;
  const payload = accessToken.split('.')[1];
  if (!payload) return EMPTY;

  let parsed: unknown;
  try {
    parsed = JSON.parse(base64UrlDecode(payload));
  } catch {
    return EMPTY;
  }
  if (typeof parsed !== 'object' || parsed === null) return EMPTY;
  const claims = parsed as Record<string, unknown>;

  const realmAccess = claims.realm_access;
  const roles =
    typeof realmAccess === 'object' && realmAccess !== null
      ? ((realmAccess as Record<string, unknown>).roles ?? [])
      : [];

  return {
    roles: Array.isArray(roles) ? roles.filter((r): r is string => typeof r === 'string') : [],
    partyId: readString(claims, 'party_id'),
    tenantId: readString(claims, 'tenant_id'),
    preferredUsername: readString(claims, 'preferred_username'),
    name: readString(claims, 'name'),
    email: readString(claims, 'email'),
  };
}

/** Structured staff roles, so nav gating reads as a field rather than a string match. */
export function staffRoles(identity: TokenIdentity): StaffRoles {
  const has = (role: keyof StaffRoles) => identity.roles.includes(role);
  return {
    UNDERWRITER: has('UNDERWRITER'),
    SENIOR_UNDERWRITER: has('SENIOR_UNDERWRITER'),
    CLAIMS_ASSESSOR: has('CLAIMS_ASSESSOR'),
    CLAIMS_MANAGER: has('CLAIMS_MANAGER'),
    FINANCE_OFFICER: has('FINANCE_OFFICER'),
    CUSTOMER_SERVICE_REP: has('CUSTOMER_SERVICE_REP'),
    ADMIN: has('ADMIN'),
  };
}

/**
 * The Finance nav group. Mirrors the backend's own expression on every finance
 * endpoint: `hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))`.
 */
export function canSeeFinance(identity: TokenIdentity): boolean {
  const roles = staffRoles(identity);
  return roles.FINANCE_OFFICER || roles.ADMIN;
}

/**
 * Authoring or pricing a product. ADMIN alone, mirroring
 * `hasRole('REALM_STAFF') and hasRole('ADMIN')` on `POST /products` and its versions.
 *
 * Deliberately NOT the `FINANCE_OFFICER or ADMIN` pair every other gated surface uses. Pricing
 * a life product is rare actuarial set-up, and this is the one capability that makes ADMIN mean
 * anything: before it, ADMIN carried nothing FINANCE_OFFICER did not, on any endpoint on the
 * platform, while PRODUCT.md claimed it carried product authoring.
 *
 * Reading the catalogue is not gated here and must not be — issuing a policy needs it.
 */
export function canAuthorProducts(identity: TokenIdentity): boolean {
  return staffRoles(identity).ADMIN;
}

/** A display name that degrades gracefully -- tokens vary in which claims they carry. */
export function displayName(identity: TokenIdentity): string {
  return identity.name ?? identity.preferredUsername ?? identity.email ?? 'Signed in';
}

/** Initials for the avatar; the fallback IS the avatar here, since parties have no photos. */
export function initials(source: string): string {
  const parts = source
    .replace(/[._@-]+/g, ' ')
    .trim()
    .split(/\s+/)
    .filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return (parts[0] ?? '').slice(0, 2).toUpperCase();
  return ((parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')).toUpperCase();
}

/**
 * Deterministic hue from an identifier, so the same person is always the same
 * colour. A hash rather than a palette index because party ids are UUIDs.
 */
export function avatarHue(seed: string): number {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) {
    hash = (hash * 31 + seed.charCodeAt(i)) | 0;
  }
  return Math.abs(hash) % 360;
}

/**
 * Putting a group scheme on risk, or admitting a life to one. Mirrors
 * `hasRole('UNDERWRITER')` on `POST /group-schemes` and `POST /group-schemes/{n}/members`.
 *
 * Deliberately NOT the FINANCE_OFFICER-or-ADMIN pair the finance surfaces use, and
 * deliberately not ADMIN either: this is the same act as deciding an underwriting case — it
 * accepts lives, fixes the free cover limit and the premium, and the scheme is on risk
 * immediately — so it takes the same role that endpoint takes.
 *
 * Reading a scheme and its schedule is NOT gated and must not be: a claims assessor has to be
 * able to check whether a life was covered when a death is reported.
 */
export function canUnderwriteGroupSchemes(identity: TokenIdentity): boolean {
  return staffRoles(identity).UNDERWRITER;
}
