import { expect } from '@playwright/test';

/**
 * The texts the platform sent, read from the local mock SMS gateway (backend/infra/docker-compose.yml's
 * mock-sms-gateway, a WireMock) -- for the specs whose person has to read a code off their phone.
 *
 * Needs the backend running with SMS_GATEWAY_LIVE=true and the gateway on that mock, its default: with sending off,
 * the platform records the text as not sent and nothing reaches the journal (see playwright.config.ts).
 */
const JOURNAL = 'http://localhost:8083/__admin/requests';
/** Short of the platform's own one-code-a-minute caps, so an older code to the same number can't be taken for a new one. */
const CLOCK_ALLOWANCE_MS = 5_000;

interface Logged {
  request: { body?: string; loggedDate: number };
}

interface Text {
  to: string;
  text: string;
}

/** Every text sent to `msisdn` (no plus: 255…) since `since`, newest first. */
async function textsTo(msisdn: string, since: number): Promise<Text[]> {
  const response = await fetch(`${JOURNAL}?limit=100`);
  if (!response.ok) throw new Error(`The mock SMS gateway's journal answered ${response.status}`);
  const { requests } = (await response.json()) as { requests: Logged[] };
  return requests
    // The journal stamps with the container's clock, which can run a little behind this machine's.
    .filter((r) => r.request.loggedDate >= since - CLOCK_ALLOWANCE_MS)
    .map((r) => {
      try {
        return JSON.parse(r.request.body ?? '') as Text;
      } catch {
        return null;
      }
    })
    .filter((t): t is Text => t !== null && t.to === msisdn);
}

/**
 * The six-digit code in the newest text to `msisdn` since `since` (a Date.now() taken before the code was asked for),
 * waiting for it to arrive: the platform texts after its own transaction commits, not before it answers.
 */
export async function codeTextedTo(msisdn: string, since: number): Promise<string> {
  let code: string | undefined;
  await expect
    .poll(
      async () => {
        const [newest] = await textsTo(msisdn, since);
        code = newest?.text.match(/\b(\d{6})\b/)?.[1];
        return code;
      },
      { message: `a six-digit code texted to ${msisdn}`, timeout: 20_000 },
    )
    .toBeTruthy();
  return code as string;
}
