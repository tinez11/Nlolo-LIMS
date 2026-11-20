import { describe, expect, it } from 'vitest';
import { changePasswordUrl } from './changePassword';

describe('changePasswordUrl', () => {
  it("opens Authentik's change-password flow and comes back to this page", () => {
    window.history.replaceState({}, '', '/customers/policies/POL-1');
    expect(changePasswordUrl()).toBe(
      'http://localhost:9100/if/flow/lifeplatform-change-password/?return_to=/customers/policies/POL-1',
    );
  });

  it('carries the page path only: Authentik accepts nothing else to come back to', () => {
    window.history.replaceState({}, '', '/staff/policies?tab=open#top');
    expect(changePasswordUrl()).toBe(
      'http://localhost:9100/if/flow/lifeplatform-change-password/?return_to=/staff/policies',
    );
  });
});
