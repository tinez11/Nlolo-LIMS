import { expect, test } from './agentSession';

/**
 * `agent.senior` supervises `agent.junior` (LIC-JUNIOR-001) in the seeded hierarchy, so the picker
 * has something real to offer.
 *
 * The picker only ever lists what `GET /agents/me/team` returns, which the server scopes from the
 * token's own party. That an id OUTSIDE the downline is refused is pinned server-side by
 * DistributionContractTest (a downline agent reading its supervisor is 403) -- the place that
 * decision is actually enforced, rather than here where the UI merely declines to offer it.
 */
test.describe('agents commission team picker', () => {
  test('a supervisor picks a downline agent and sees that agent, and the choice survives a reload', async ({
    page,
  }) => {
    await page.goto('/agents/commission');
    const picker = page.getByLabel('Agent', { exact: true });
    await expect(picker).toBeVisible();
    await expect(picker.locator('option', { hasText: /^Me$/ })).toHaveCount(1);

    const junior = picker.locator('option', { hasText: 'LIC-JUNIOR-001' });
    await expect(junior).toHaveCount(1);
    const juniorId = (await junior.getAttribute('value')) as string;
    await picker.selectOption(juniorId);

    await expect(page).toHaveURL(new RegExp(`\\?agent=${juniorId}$`));
    await expect(page.getByText(/LIC-JUNIOR-001/).first()).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Totals' })).toBeVisible();

    await page.reload();
    await expect(page.getByLabel('Agent', { exact: true })).toHaveValue(juniorId);
  });
});
