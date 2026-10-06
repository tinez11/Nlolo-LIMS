import { expect, request as apiRequest, test } from '@playwright/test';
import { staffToken } from './creditLife';

/**
 * IFRS 17 I3d through the real stack: an ended quarter of a treaty is prepared from its bordereaux, completed from the
 * reinsurer's statement -- a real file uploaded, since I4's document bug hid behind fake references -- submitted, and
 * approved by a finance approver, which posts it.
 *
 * A settled quarter cannot be settled again, so every run uses up one. The spec looks for a treaty with an ended
 * quarter whose every month's bordereau is written and that has no live statement, and skips -- saying so -- when the
 * tenant has none left (more appear as each quarter ends and the bordereau job writes its months).
 */

const API = 'http://localhost:8080';

interface Treaty { treatyId: string; reinsurerName: string; effectiveFrom: string; effectiveTo?: string | null }
interface Statement { quarter: string; status: string }
interface Bordereau { period: string; premium: string }

function quarterMonths(quarter: string): string[] {
  const [y, q] = [Number(quarter.slice(0, 4)), Number(quarter.slice(6))];
  return [1, 2, 3].map((n) => `${y}-${String((q - 1) * 3 + n).padStart(2, '0')}`);
}

function endedQuarters(from: string, now = new Date()): string[] {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Dar_es_Salaam' }).format(now);
  const index = (d: string) => Number(d.slice(0, 4)) * 4 + Math.floor((Number(d.slice(5, 7)) - 1) / 3);
  const out: string[] = [];
  for (let i = index(from); i < index(today); i++) out.push(`${Math.floor(i / 4)}-Q${(i % 4) + 1}`);
  return out.reverse();
}

/**
 * A treaty and quarter the server will settle: ended, every month inside the treaty's dates written, no live statement.
 * One whose bordereaux charged premium is preferred -- an all-zero quarter posts no journal, which proves less.
 */
async function findSettleable(): Promise<{ treaty: Treaty; quarter: string; charged: boolean } | null> {
  const http = await apiRequest.newContext();
  let quiet: { treaty: Treaty; quarter: string; charged: boolean } | null = null;
  try {
    const headers = { Authorization: `Bearer ${await staffToken(http, 'staff.finance')}` };
    const treaties = (await (await http.get(`${API}/treaties`, { headers })).json()) as Treaty[];
    for (const treaty of treaties) {
      const quarters = endedQuarters(treaty.effectiveFrom);
      if (quarters.length === 0) continue;
      const statements = (await (await http.get(`${API}/reinsurance-statements`, {
        headers, params: { treatyId: treaty.treatyId },
      })).json()) as Statement[];
      const live = new Set(statements.filter((s) => s.status !== 'REJECTED').map((s) => s.quarter));
      const bordereaux = (await (await http.get(`${API}/treaties/${treaty.treatyId}/bordereaux`, { headers }))
        .json()) as Bordereau[];
      const written = new Map(bordereaux.map((b) => [b.period, Number(b.premium)]));
      for (const quarter of quarters) {
        const needed = quarterMonths(quarter).filter((m) => `${m}-31` >= treaty.effectiveFrom
          && (!treaty.effectiveTo || `${m}-01` <= treaty.effectiveTo));
        if (!live.has(quarter) && needed.length > 0 && needed.every((m) => written.has(m))) {
          const charged = needed.some((m) => (written.get(m) ?? 0) > 0);
          if (charged) return { treaty, quarter, charged };
          quiet ??= { treaty, quarter, charged };
        }
      }
    }
    return quiet;
  } finally {
    await http.dispose();
  }
}

test.describe('reinsurance statement', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('a quarter is prepared, completed from the reinsurer statement, approved by a finance approver and posted', async ({
    page,
    browser,
  }) => {
    const found = await findSettleable();
    test.skip(found === null, 'no treaty in this tenant has an ended quarter left to settle');
    const { treaty, quarter, charged } = found!;

    await page.goto(`/staff/treaties/${treaty.treatyId}`);
    await page.getByRole('button', { name: `Prepare statement for ${quarter}` }).click();
    await expect(page.getByRole('region', { name: 'Statement' })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('heading', { name: `${treaty.reinsurerName} · ${quarter}` })).toBeVisible();

    await page.getByLabel('Reason').fill(`${quarter} statement agreed with ${treaty.reinsurerName} (e2e)`);
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('region', { name: 'Statement' })).toContainText('(e2e)', { timeout: 15_000 });

    await page.getByLabel('Statement file').setInputFiles({
      name: `${quarter}-statement.pdf`,
      mimeType: 'application/pdf',
      buffer: Buffer.from(`%PDF-1.4\n% e2e reinsurance statement ${quarter}\n`),
    });
    await expect(page.getByRole('region', { name: 'Documents' }).locator('li')).toHaveCount(1, { timeout: 15_000 });

    await page.getByRole('button', { name: 'Submit for approval' }).click();
    await expect(page.getByText('Waiting for a finance approver other than you.')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: 'Approve and post' })).toHaveCount(0);
    const url = page.url();

    const approverContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance-approver.json' });
    try {
      const approver = await approverContext.newPage();
      await approver.goto('/staff/reinsurance-statements');
      await expect(approver.getByRole('table', { name: 'Reinsurance statements' })).toContainText(quarter, { timeout: 15_000 });
      await approver.goto(url);
      if (charged) {
        // The approver sees what will post: R-01 clears the quarter's premium payable (Dr 1430).
        await expect(approver.getByRole('table', { name: 'Journal' })).toContainText('1430');
      }
      await approver.getByRole('button', { name: 'Approve and post' }).click();
      await expect(approver.getByText('Posted', { exact: true })).toBeVisible({ timeout: 15_000 });
      if (charged) {
        await expect(approver.getByRole('region', { name: 'Journal preview' })).toContainText('Journal posted');
      }
    } finally {
      await approverContext.close();
    }
  });
});
