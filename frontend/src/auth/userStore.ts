import { WebStorageStateStore, type StateStore } from 'oidc-client-ts';

/**
 * Token storage policy.
 *
 * Tokens are held IN MEMORY ONLY. This console reads policyholder PII and triggers
 * real payouts, so a long-lived access or refresh token sitting in web storage is a
 * standing prize for any XSS. A page reload costs one silent redirect against
 * Keycloak's SSO cookie instead.
 *
 * `sessionStorage` still holds the transient PKCE verifier and state, because those
 * must survive the redirect to Keycloak and back -- but they are single-use and
 * worthless once redeemed.
 *
 * Keys are namespaced PER REALM. Without that, moving between /staff and /agents
 * would collide on the same storage keys and one realm's tokens could be handed to
 * the other realm's provider.
 */

/** In-memory StateStore: satisfies oidc-client-ts but never touches disk. */
class MemoryStateStore implements StateStore {
  private readonly entries = new Map<string, string>();

  set(key: string, value: string): Promise<void> {
    this.entries.set(key, value);
    return Promise.resolve();
  }

  get(key: string): Promise<string | null> {
    return Promise.resolve(this.entries.get(key) ?? null);
  }

  remove(key: string): Promise<string | null> {
    const existing = this.entries.get(key) ?? null;
    this.entries.delete(key);
    return Promise.resolve(existing);
  }

  getAllKeys(): Promise<string[]> {
    return Promise.resolve([...this.entries.keys()]);
  }
}

/** Where the User (and therefore the tokens) lives: memory, per realm. */
export function createUserStore(): StateStore {
  return new MemoryStateStore();
}

/**
 * Where the PKCE verifier/state live across the redirect. sessionStorage rather
 * than localStorage so a second tab cannot consume this tab's in-flight login, and
 * so it dies with the tab.
 */
export function createStateStore(realm: string): StateStore {
  return new WebStorageStateStore({
    store: window.sessionStorage,
    prefix: `lifeplatform.${realm}.`,
  });
}
