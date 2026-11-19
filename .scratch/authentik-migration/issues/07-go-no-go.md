# Go or no-go on Authentik

Type: grilling
Status: resolved
Blocked by: 01, 02, 03, 04, 05, 06, 11
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

Given the six research findings, can Authentik carry every behaviour in the map's Notes without custom code inside Authentik? Where it can't, is the workaround acceptable (and what is it), or does that gap end the migration?

A "no" closes the map with the reasons recorded. A "go" unblocks the design tickets.

## Answer

**Go, conditional on one test** (the user, 2026-11-19). Every behaviour has a configuration route or an accepted change, and nothing needs code inside Authentik. The one unproven link is silently setting up the staff phone at first sign-in. [Test the silent staff phone set-up](13-test-silent-phone-setup.md) proves it or decides the fallback before any design ticket starts.

The decisions behind the go:

1. **The reset stays a typed code.**
   - On the platform's reset page, the backend texts today's six-digit code (15 minutes, today's wording and caps) and checks it.
   - Only then does it ask Authentik for a recovery link and send the browser straight to it. The link is never texted.
   - `ResetCodes` logic moves from the Keycloak extension into the backend.
2. **Wrong staff sign-in codes:** accept Authentik slowing retries, plus the backend's send caps (five an hour, one a minute). The "three wrong codes end the attempt" rule is dropped.
3. **The 10-hour session cap is kept by the console.** It forces a sign-out 10 hours after sign-in, using the token's `auth_time`. The 30-minute idle timeout and Authentik's own 10-hour sign-in session stay as well.
4. **"Your password changed"** is texted for the person's own change or reset only, as today. Admin and API password sets are filtered out. A temporary password is followed by a forced change, which does text.
5. **PKCE S256** is required by a policy in the blueprint.
6. **Signing-key rotation** is a planned operation (announced, at a quiet hour, everyone signs in again), written into the deployment guide.
