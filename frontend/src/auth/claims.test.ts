import { describe, expect, it } from 'vitest';
import {
  avatarHue,
  canSeeFinance,
  displayName,
  initials,
  readIdentity,
  staffRoles,
} from './claims';

/**
 * Builds a STRUCTURALLY REALISTIC but unsigned token, purely to exercise claim
 * decoding. This is deliberately not a stand-in for authentication: the e2e suite
 * authenticates against the real Keycloak container, because this project has twice
 * shipped a green suite over a completely unusable real credential path by letting
 * tests mint their own identities.
 */
function unsignedToken(claims: Record<string, unknown>): string {
  const b64 = (obj: unknown) => {
    // Encode as UTF-8 bytes before base64, which is what Keycloak actually emits.
    // `btoa(JSON.stringify(...))` would encode 'é' as a single Latin-1 byte and
    // produce a token no real IdP ever issues.
    const bytes = new TextEncoder().encode(JSON.stringify(obj));
    const binary = String.fromCharCode(...bytes);
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  };
  return `${b64({ alg: 'none' })}.${b64(claims)}.`;
}

describe('readIdentity', () => {
  it('reads realm_access.roles, which is what the backend authorizes on', () => {
    const token = unsignedToken({
      realm_access: { roles: ['UNDERWRITER', 'ADMIN'] },
      tenant_id: '11111111-1111-1111-1111-111111111111',
      preferred_username: 'staff.underwriter',
    });
    const identity = readIdentity(token);
    expect(identity.roles).toEqual(['UNDERWRITER', 'ADMIN']);
    expect(identity.tenantId).toBe('11111111-1111-1111-1111-111111111111');
    expect(identity.preferredUsername).toBe('staff.underwriter');
  });

  it('returns the same object for the same token, so an effect depending on it does not re-run every render', () => {
    // useNavBadges lists the identity in its dependencies; a fresh object per render looped its fetch forever.
    const token = unsignedToken({ realm_access: { roles: ['ADMIN'] }, sub: 'u-1' });
    const first = readIdentity(token);
    expect(readIdentity(token)).toBe(first);
    const other = readIdentity(unsignedToken({ realm_access: { roles: ['UNDERWRITER'] }, sub: 'u-2' }));
    expect(other).not.toBe(first);
    expect(other.roles).toEqual(['UNDERWRITER']);
    expect(readIdentity(token)).toEqual(first);
  });

  it('reads party_id where the realm issues one', () => {
    expect(readIdentity(unsignedToken({ party_id: 'abc' })).partyId).toBe('abc');
    // Staff and regulator realms have no party_id mapper at all.
    expect(readIdentity(unsignedToken({})).partyId).toBeNull();
  });

  it('decodes non-ASCII names without mangling them', () => {
    expect(readIdentity(unsignedToken({ name: 'Amina Hassanié' })).name).toBe('Amina Hassanié');
  });

  it.each([
    ['undefined', undefined],
    ['empty', ''],
    ['not a JWT', 'garbage'],
    ['bad base64 payload', 'a.!!!!.c'],
    ['payload is not an object', `a.${btoa('"nope"')}.c`],
  ])('returns an empty identity for %s rather than throwing', (_label, token) => {
    const identity = readIdentity(token as string | undefined);
    expect(identity.roles).toEqual([]);
    expect(identity.tenantId).toBeNull();
  });

  it('ignores non-string entries in the roles array', () => {
    const token = unsignedToken({ realm_access: { roles: ['ADMIN', 42, null] } });
    expect(readIdentity(token).roles).toEqual(['ADMIN']);
  });
});

describe('canSeeFinance', () => {
  // Mirrors the backend expression on every finance endpoint:
  // hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))
  it('admits FINANCE_OFFICER and ADMIN', () => {
    expect(canSeeFinance(readIdentity(unsignedToken({ realm_access: { roles: ['FINANCE_OFFICER'] } })))).toBe(true);
    expect(canSeeFinance(readIdentity(unsignedToken({ realm_access: { roles: ['ADMIN'] } })))).toBe(true);
  });

  it('excludes the operational staff roles', () => {
    for (const role of ['UNDERWRITER', 'CLAIMS_ASSESSOR', 'CLAIMS_MANAGER']) {
      expect(canSeeFinance(readIdentity(unsignedToken({ realm_access: { roles: [role] } })))).toBe(
        false,
      );
    }
  });

  // CUSTOMER_SERVICE_REP is defined in the staff realm but referenced by ZERO
  // @PreAuthorize expressions, so it grants nothing beyond the shared read surface.
  it('excludes CUSTOMER_SERVICE_REP, which the backend never checks anywhere', () => {
    const identity = readIdentity(
      unsignedToken({ realm_access: { roles: ['CUSTOMER_SERVICE_REP'] } }),
    );
    expect(canSeeFinance(identity)).toBe(false);
    expect(staffRoles(identity).CUSTOMER_SERVICE_REP).toBe(true);
  });
});

describe('displayName', () => {
  it('prefers name, then username, then email', () => {
    expect(displayName(readIdentity(unsignedToken({ name: 'A', preferred_username: 'b' })))).toBe('A');
    expect(displayName(readIdentity(unsignedToken({ preferred_username: 'b', email: 'c@d' })))).toBe('b');
    expect(displayName(readIdentity(unsignedToken({ email: 'c@d' })))).toBe('c@d');
  });

  it('never renders empty', () => {
    expect(displayName(readIdentity(undefined))).toBe('Signed in');
  });
});

describe('initials', () => {
  it.each([
    ['Halima Underwriter', 'HU'],
    ['staff.underwriter', 'SU'],
    ['amina@example.tz', 'AE'],
    ['Cher', 'CH'],
    ['a', 'A'],
    ['', '?'],
    ['   ', '?'],
  ])('%s -> %s', (input, expected) => {
    expect(initials(input)).toBe(expected);
  });
});

describe('avatarHue', () => {
  it('is stable for the same seed', () => {
    expect(avatarHue('party-1')).toBe(avatarHue('party-1'));
  });

  it('is always a valid hue', () => {
    for (const seed of ['a', 'party-1', crypto.randomUUID(), '']) {
      const hue = avatarHue(seed);
      expect(hue).toBeGreaterThanOrEqual(0);
      expect(hue).toBeLessThan(360);
    }
  });

  it('spreads different seeds across the wheel', () => {
    const hues = new Set(
      Array.from({ length: 40 }, (_, i) => avatarHue(`party-${i}`)),
    );
    // Not a uniformity proof -- just that it is not collapsing to one colour.
    expect(hues.size).toBeGreaterThan(20);
  });
});
