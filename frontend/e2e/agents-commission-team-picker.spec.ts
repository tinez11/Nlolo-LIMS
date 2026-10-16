import { expect, test } from './agentSession';

/**
 * `agent.senior` supervises `agent.junior` (LIC-JUNIOR-001) in the seeded hierarchy, so the picker
 * has something real to offer.
 *
 * The picker only ever lists what `GET /agents/me/team` returns, which the server scopes from the
 * token's own party. That an id OUTSIDE the downline is refused is pinned server-side by
 * DistributionContractTest (a downline agent reading its supervisor is 403) -- the place that
 * decision is actually enforced, rather than here where the UI merely declines to offer it.
 *
 * `getByLabel('Agent')` is deliberately NOT exact: a wrapping `<label>` takes its text from all of
 * its content, so this select's accessible name is "Agent" followed by every option's own text.
 * Every other select on this console is located the same loose way (see `getByLabel('Product')`).
 */
test.describe('agents commission team picker', () => {
  test('a supervisor picks a downline agent and sees that agent, and the choice survives a reload', async ({
    page,
  }) => {
    // Settle the SSO round trip on the realm's own landing route first. Going straight to a deep
    // link as the FIRST navigation sends the OIDC redirect_uri to `/agents`, so the browser comes
    // back from Keycloak on the landing page and the picker is nowhere -- which is how this spec
    // failed on its first run, with the call log still mid-redirect. That landing is the Dashboard
    // since 2026-10-14.
    await page.goto('/agents');
    await expect(page.getByRole('heading', { name: 'Dashboard', exact: true })).toBeVisible();

    await page.goto('/agents/commission');
    const picker = page.getByLabel('Agent');
    await expect(picker).toBeVisible();
    await expect(picker.locator('option', { hasText: /^Me$/ })).toHaveCount(1);

    const junior = picker.locator('option', { hasText: 'LIC-JUNIOR-001' });
    await expect(junior).toHaveCount(1);
    const juniorId = (await junior.getAttribute('value')) as string;
    await picker.selectOption(juniorId);

    await expect(page).toHaveURL(new RegExp(`\\?agent=${juniorId}$`));
    // The byline names whose figures are on screen -- the downline agent's, not the supervisor's.
    await expect(page.getByText(/LIC-JUNIOR-001/).first()).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Totals' })).toBeVisible();

    await page.reload();
    await expect(page.getByLabel('Agent')).toHaveValue(juniorId);
  });
});
