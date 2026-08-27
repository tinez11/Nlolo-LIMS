import { describe, expect, it } from 'vitest';
import { REALM_CONFIG, type Realm } from '@/auth/realms';
import { NAV_GROUPS, REALM_HOME, SCREENS, navFor } from '@/screens';

/**
 * The manifest is the single source for both the router and the sidebar, so these
 * assert the invariants that used to be unenforceable when the two were separate
 * hand-maintained lists.
 */

const realms = Object.keys(REALM_CONFIG) as Realm[];
const built = realms.filter((realm) => SCREENS[realm].length > 0);

/** An identity that passes every gate, so nav filtering doesn't hide entries here. */
const superuser = {
  roles: ['REALM_STAFF', 'FINANCE_OFFICER', 'ADMIN'],
  preferredUsername: 'test',
} as unknown as Parameters<typeof navFor>[1];

describe('screen manifest', () => {
  it('has no duplicate paths within a realm', () => {
    for (const realm of realms) {
      const paths = SCREENS[realm].map((s) => s.path);
      expect(new Set(paths).size, `duplicate path in ${realm}`).toBe(paths.length);
    }
  });

  it('places every nav screen in a group its realm actually declares', () => {
    for (const realm of realms) {
      const declared = new Set(NAV_GROUPS[realm].map((g) => g.id));
      for (const screen of SCREENS[realm]) {
        if (screen.reach === 'drill-in') continue;
        expect(declared, `${realm}/${screen.path} is in group "${screen.reach.group}"`).toContain(
          screen.reach.group,
        );
      }
    }
  });

  it('redirects a realm index to a path that exists', () => {
    for (const realm of built) {
      const home = REALM_HOME[realm];
      expect(home, `${realm} has screens but no home`).toBeTruthy();
      expect(
        SCREENS[realm].map((s) => s.path),
        `${realm} home "${home}" is not a screen`,
      ).toContain(home);
    }
  });

  it('gives every built realm at least one way into the sidebar', () => {
    for (const realm of built) {
      // A realm whose every screen is drill-in only would render an empty
      // sidebar, leaving the console reachable only by typing URLs.
      expect(navFor(realm, superuser).length, `${realm} has no nav groups`).toBeGreaterThan(0);
    }
  });

  it('mounts no subtree for a realm with no screens', () => {
    for (const realm of realms.filter((r) => !built.includes(r))) {
      expect(NAV_GROUPS[realm]).toHaveLength(0);
      expect(REALM_HOME[realm]).toBeNull();
    }
  });

  it('keeps every nav item pointing at a real screen', () => {
    for (const realm of built) {
      const paths = new Set(SCREENS[realm].map((s) => s.path));
      for (const group of navFor(realm, superuser)) {
        for (const item of group.items) {
          expect(paths, `nav item "${item.label}" -> ${item.to}`).toContain(item.to);
        }
      }
    }
  });

  it('hides the finance group from an identity without the role', () => {
    const operationsOnly = { roles: ['REALM_STAFF'] } as unknown as Parameters<typeof navFor>[1];
    const labels = navFor('staff', operationsOnly).map((g) => g.label);
    expect(labels).toContain('Operations');
    expect(labels).not.toContain('Finance');
  });

  it('nav order follows group order, not screen order', () => {
    // Operations before Finance, as declared in NAV_GROUPS -- the sidebar's order
    // is the group list's order, so reordering screens cannot reshuffle it.
    expect(navFor('staff', superuser).map((g) => g.label)).toEqual(['Operations', 'Finance']);
  });
});
