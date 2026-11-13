import { type APIRequestContext, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { staffToken } from './creditLife';
import { todayIso } from './dates';

/**
 * Treaty fixtures that live with the rule of 2026-11-08: only one treaty is in force on any day, because each policy
 * is ceded to one treaty and a second in force beside it got no business at all. The server refuses a treaty whose
 * dates overlap one already active, so the e2e can no longer create "a treaty effective today" on every run -- after
 * the first run of a day one is already in force today, and it cannot be ended before today.
 *
 * Instead: a cession test uses the treaty that takes today's policies (creating one only when none is in force), and
 * the authoring test creates its treaty on the first day nothing else is in force -- after ending whichever treaty is
 * open-ended, at the earliest day it may end.
 */

const API = 'http://localhost:8080';

export interface Treaty {
  treatyId: string;
  reinsurerName: string;
  treatyType: string;
  status: string;
  standing: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  cessionPercent: string | null;
  takesNewPolicies: boolean;
}

/** An ISO date `days` after another, by the calendar. */
export function plusDays(iso: string, days: number): string {
  const [y, m, d] = iso.split('-').map(Number) as [number, number, number];
  const date = new Date(Date.UTC(y, m - 1, d + days));
  return date.toISOString().slice(0, 10);
}

const later = (a: string, b: string) => (a > b ? a : b);

async function treaties(http: APIRequestContext, token: string): Promise<Treaty[]> {
  const response = await http.get(`${API}/treaties`, { headers: { Authorization: `Bearer ${token}` } });
  expect(response.ok(), `GET /treaties -> ${response.status()}`).toBeTruthy();
  return (await response.json()) as Treaty[];
}

/**
 * The first day no active treaty is in force, from today: any open-ended treaty is first ended at the earliest day
 * it may end (today, or its own start if that is later). A treaty authored from this day overlaps nothing.
 */
export async function firstFreeDay(http: APIRequestContext): Promise<string> {
  const token = await staffToken(http, 'staff.finance');
  const today = todayIso();
  for (const t of (await treaties(http, token)).filter((x) => x.status === 'ACTIVE' && x.effectiveTo === null)) {
    const response = await http.post(`${API}/treaties/${t.treatyId}/end`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { endsOn: later(today, t.effectiveFrom) },
    });
    expect(response.ok(), `ending ${t.reinsurerName} -> ${response.status()} ${await response.text()}`).toBeTruthy();
  }
  const lastEnd = (await treaties(http, token))
    .filter((x) => x.status === 'ACTIVE' && x.effectiveTo !== null)
    .reduce((max, x) => later(max, x.effectiveTo!), plusDays(today, -1));
  return plusDays(lastEnd, 1);
}

/**
 * The treaty a policy issued today is ceded to. When none is in force, a 50% quota share is created for today, ending
 * the day before any treaty that starts later -- so it overlaps nothing.
 */
export async function treatyForToday(http: APIRequestContext): Promise<Treaty> {
  const token = await staffToken(http, 'staff.finance');
  const all = await treaties(http, token);
  const inForce = all.find((t) => t.takesNewPolicies);
  if (inForce) return inForce;
  const today = todayIso();
  const nextStart = all
    .filter((t) => t.status === 'ACTIVE' && t.effectiveFrom > today)
    .map((t) => t.effectiveFrom)
    .sort()[0];
  const response = await http.post(`${API}/treaties`, {
    headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': randomUUID() },
    data: {
      reinsurerName: `E2E Re ${Date.now()}`,
      treatyType: 'QUOTA_SHARE',
      retentionLimit: { amount: '0.00', currencyCode: 'TZS' },
      cessionPercent: '50.00',
      commissionPercent: '0',
      effectiveFrom: today,
      effectiveTo: nextStart ? plusDays(nextStart, -1) : null,
    },
  });
  const body = await response.text();
  expect(response.ok(), `POST /treaties -> ${response.status()} ${body}`).toBeTruthy();
  return approveTreatyAsSecondPerson(http, (JSON.parse(body) as Treaty).treatyId);
}

/**
 * A new treaty cedes nothing until a second person approves it (2026-11-12, finance finding 170). Finance proposes in
 * these specs, so the finance approver -- who holds reinsurance.approve, as an admin does not -- is the second person.
 */
export async function approveTreatyAsSecondPerson(http: APIRequestContext, treatyId: string): Promise<Treaty> {
  const token = await staffToken(http, 'staff.finance-approver');
  const response = await http.post(`${API}/treaties/${treatyId}/approval`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {},
  });
  const body = await response.text();
  expect(response.ok(), `approving treaty ${treatyId} -> ${response.status()} ${body}`).toBeTruthy();
  return JSON.parse(body) as Treaty;
}
