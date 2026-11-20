import { expect, request as apiRequest, test, type APIRequestContext } from '@playwright/test';
import { randomInt, randomUUID } from 'node:crypto';
import { apiToken, fillSignIn } from './signIn';
import { codeTextedTo } from './smsGateway';

/**
 * "Forgot your password?", the happy path (ticket 07 of .scratch/authentik-cutover): an agent asks for a code on the
 * platform's public /reset page, types the code texted to them, chooses a new password on Authentik's page, and signs
 * in with it.
 *
 * The agent is made fresh for each run, over the API -- a client, verified, made an agent, and given a login -- so the
 * run never changes a shared dev login's password, and a number is never reset twice within the platform's caps (three
 * codes an hour for each number). A reset finds only an agent's or a customer's login whose username is its phone
 * number, which is what a login made this way has.
 */
const API = 'http://localhost:8080';

async function post(http: APIRequestContext, token: string, path: string, data: unknown): Promise<Record<string, unknown>> {
  const response = await http.post(`${API}${path}`, {
    headers: { authorization: `Bearer ${token}`, 'idempotency-key': randomUUID() },
    data,
  });
  expect(response.ok(), `POST ${path}: ${response.status()} ${await response.text()}`).toBeTruthy();
  const body = await response.text(); // the KYC decision answers with no body
  return (body ? JSON.parse(body) : {}) as Record<string, unknown>;
}

/** A new agent with a login, as the platform makes one; returns the login's username, its phone number. */
async function newAgentWithLogin(stamp: string, phone: string): Promise<string> {
  const http = await apiRequest.newContext();
  try {
    // Agents and customers register clients; customer service verifies them; finance onboards an agent; an
    // administrator gives the login (the same identities seed-dev-data.sh uses for each step).
    const party = await post(http, await apiToken(http, 'agent.senior'), '/parties/individuals', {
      fullName: `Reset Tester ${stamp}`,
      dateOfBirth: '1991-03-14',
      sex: 'FEMALE',
      idType: 'NATIONAL_ID',
      idNumber: `E2E-RESET-${stamp}`,
      contactInfo: { phoneNumber: phone },
    });
    await post(http, await apiToken(http, 'staff.service'), `/parties/${party.partyId}/kyc`, {
      status: 'VERIFIED',
      evidenceDocumentRef: `e2e:reset-${stamp}`,
    });
    const agent = await post(http, await apiToken(http, 'staff.finance'), '/agents', {
      partyId: party.partyId,
      licenseNumber: `LIC-RESET-${stamp}`,
      licenseExpiryDate: '2030-01-01',
    });
    const issued = await post(http, await apiToken(http, 'staff.admin'), `/agents/${agent.agentId}/login`, {});
    return (issued.login as { username: string }).username;
  } finally {
    await http.dispose();
  }
}

test('an agent resets a forgotten password with a texted code and signs in with the new one', async ({ browser, page }) => {
  // Four API sign-ins, a texted code, two Authentik flows and a sign-in: well past the suite's one-sign-in budget.
  test.setTimeout(180_000);
  const stamp = `${Date.now()}`.slice(-9);
  const msisdn = `25579${String(randomInt(10_000_000)).padStart(7, '0')}`;
  const username = await newAgentWithLogin(stamp, `+${msisdn}`);
  expect(username, 'the login is named by its phone number').toBe(`+${msisdn}`);
  const newPassword = `Reset-e2e-${stamp}`;

  // From Authentik's sign-in page, as a person who has forgotten would start.
  await page.goto('/agents');
  await page.getByRole('link', { name: /Forgot .*password/i }).click({ timeout: 30_000 });
  await page.waitForURL((url) => url.href.startsWith('http://localhost:5173/reset'), { timeout: 30_000 });

  // Typed the way people write it, without the country code; the page and the backend make it +255.
  await page.getByLabel('Your phone number').fill(`0${msisdn.slice(3)}`, { timeout: 30_000 });
  const asked = Date.now();
  await page.getByRole('button', { name: 'Send me a code' }).click();
  await expect(page.getByRole('status')).toBeVisible({ timeout: 20_000 });

  await page.getByLabel('Code from the text').fill(await codeTextedTo(msisdn, asked));
  await page.getByRole('button', { name: 'Continue' }).click();

  // Authentik's "Choose a new password", opened by the recovery link the backend asked for.
  await page.getByLabel('New password', { exact: true }).fill(newPassword, { timeout: 30_000 });
  await page.getByLabel('New password again').fill(newPassword);
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.waitForURL((url) => url.origin === 'http://localhost:5173', { timeout: 30_000 });

  // A new browser, so nothing from the reset carries over: the new password is what signs in.
  const fresh = await browser.newContext({ baseURL: 'http://localhost:5173' });
  try {
    const again = await fresh.newPage();
    await again.goto('/agents');
    await fillSignIn(again, username, newPassword);
    await expect(again.getByRole('heading', { name: 'Dashboard', exact: true })).toBeVisible({ timeout: 30_000 });
  } finally {
    await fresh.close();
  }
});
