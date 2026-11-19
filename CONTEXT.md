# Nlolo Life Platform

A life-insurance administration platform for Tanzanian insurers: products, policies, premiums, claims, distribution and finance, run by staff and agents and used by customers through a portal.

## Language

### Sign-in

**Login**:
A person's account at the identity provider: the username and password they sign in with. A staff member, agent or customer has at most one.
_Avoid_: user account, IdP user, Keycloak user

**Identity provider**:
The separate service that holds logins and issues the tokens the platform trusts.
_Avoid_: realm, auth server

**Workspace**:
The console a signed-in person is acting in for one request: staff, agents, customers or regulator. One login may reach several workspaces.
_Avoid_: realm, portal (when meaning the console)

**Universal login**:
One sign-in page and one login for every workspace; the workspace is chosen after sign-in, not by which page was used.

**Job role**:
A staff duty carried by a login, such as Underwriter or Finance approver, that the platform's permissions build on.
_Avoid_: realm role, group

**Sign-in code**:
A short code texted to a staff member that they enter after their password on every sign-in.
_Avoid_: OTP, 2FA code

**Password reset**:
An agent or customer choosing a new password after proving they hold their phone, by typing a code texted to it. Staff cannot do this for themselves.
_Avoid_: recovery, forgot password
