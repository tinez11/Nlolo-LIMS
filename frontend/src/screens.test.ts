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

  it('hides every finance-gated group from an identity without the role', () => {
    const withoutFinance = { roles: ['REALM_STAFF'] } as unknown as Parameters<typeof navFor>[1];
    const labels = navFor('staff', withoutFinance).map((g) => g.label);
    expect(labels).toContain('New business');
    // Both gated groups, not just the one named Finance -- Distribution carries
    // the same predicate because onboarding an agent is a finance-role action.
    expect(labels).not.toContain('Finance');
    expect(labels).not.toContain('Distribution');
  });

  it('nav order follows the business flow, not screen order', () => {
    // A client is registered and assessed, becomes a policy, generates claims,
    // then finance and distribution settle up -- and Configuration is last
    // because authoring a product is rare set-up, not daily work. The sidebar's
    // order is NAV_GROUPS' order, so reordering screens cannot reshuffle it.
    // Clients leads: they are the entity everything else hangs off, and this screen
    // is the way into a person's policies, claims, KYC and documents.
    expect(navFor('staff', superuser).map((g) => g.label)).toEqual([
      'Clients',
      'New business',
      'Policies & claims',
      'Finance',
      'Distribution',
      'Records',
      // After Records and before Configuration: what the platform SAYS to customers is a record
      // of something that happened, not set-up. Its templates are configuration in a strict
      // sense, but they are read alongside the evidence a message was sent -- usually by the
      // same person answering the same complaint -- so splitting the two apart would serve the
      // taxonomy rather than the job.
      'Communications',
      'Configuration',
    ]);
  });

  it('never makes the nav stricter than the endpoint it fronts', () => {
    // `GET /audit-log` is hasRole('REALM_STAFF') -- any staff member may read it.
    // Putting the Event journal in a finance-gated group would hide a screen most
    // staff are authorised to see, which is how it was first written.
    const withoutFinance = { roles: ['REALM_STAFF'] } as unknown as Parameters<typeof navFor>[1];
    const items = navFor('staff', withoutFinance).flatMap((g) => g.items.map((i) => i.label));
    expect(items).toContain('Event journal');
  });

  it('gives both realms that can read clients a way to reach them', () => {
    // `GET /parties` is REALM_STAFF or REALM_AGENTS, and force-scopes an agent to
    // parties it registered. So both realms get an entry, and the Clients group is
    // ungated -- the same "nav must not be stricter than the endpoint" rule the
    // Event journal broke when it was first filed under a finance-gated group.
    //
    // Staff get TWO entries, because the register is two working areas: a natural
    // person and an organisation are reviewed by different people against different
    // evidence. BOTH must be present -- one alone would leave a whole party type
    // reachable only by editing a URL.
    const withoutFinance = { roles: ['REALM_STAFF'] } as unknown as Parameters<typeof navFor>[1];
    const staffItems = navFor('staff', withoutFinance).flatMap((g) => g.items.map((i) => i.label));
    expect(staffItems).toContain('Individuals');
    expect(staffItems).toContain('Corporate/Group');
    expect(navFor('agents', superuser).flatMap((g) => g.items.map((i) => i.label)))
      .toContain('My clients');
  });

  it('keeps the retired register path reachable, and out of the nav', () => {
    // The KYC badge's own links and staff bookmarks point at `kyc`. It survives as a
    // redirect: still routed, so the link resolves, but declared drill-in so it does
    // not appear in the sidebar as a third door onto the same register.
    const legacy = SCREENS.staff.find((s) => s.path === 'kyc');
    expect(legacy).toBeDefined();
    expect(legacy?.reach).toBe('drill-in');
  });

  it('lets both realms drill into a client record', () => {
    // The register navigates to `../parties/{id}` relative to itself, so the agents
    // realm needs its own `parties/:partyId` route or that link is a 404 there.
    for (const realm of ['staff', 'agents'] as const) {
      expect(SCREENS[realm].map((s) => s.path)).toContain('parties/:partyId');
    }
  });

  it('points the Agents nav item at a list, not at the create form', () => {
    // PLAN.md §7 recorded Agents as the one nav item aimed at a create form,
    // because `POST /agents` was the only entry point onto the domain that
    // existed. `GET /agents` retired that exception; this pins it so the item
    // cannot quietly regress to the form.
    const agents = SCREENS.staff.find((s) => s.reach !== 'drill-in' && s.reach.label === 'Agents');
    expect(agents?.path).toBe('agents');
    expect(SCREENS.staff.find((s) => s.path === 'agents/new')?.reach).toBe('drill-in');
  });

  it('declares a badge only where a real paged count exists', () => {
    const badged = SCREENS.staff
      .filter((s) => s.reach !== 'drill-in' && s.reach.badge)
      .map((s) => (s.reach as { label: string }).label);

    // Only what is expressible: one status filter at a time and no analytics
    // endpoint anywhere. Policies deliberately has none -- a policy in force is not
    // work waiting.
    //
    // Both client areas carry a KYC-pending count. The screen became a register
    // rather than a queue, but the count is still work waiting, and the badge is what
    // keeps that queue one click away now that the default filter shows everyone --
    // and it is counted PER AREA, because a single combined count on one of the two
    // items would not describe the list beside it.
    // Finance carries two counts of its own now. Arrears counts only dunning level 5 --
    // lapse recommended -- because a level-1 case is a letter, not an emergency; and field
    // receipts counts the SLA breach a live Prometheus alert already fires on, which until
    // then named a number and no receipt.
    expect(badged.sort()).toEqual([
      'Arrears',
      'Claims',
      'Corporate/Group',
      'Field receipts',
      'Individuals',
      'Underwriting',
    ]);
  });
});
