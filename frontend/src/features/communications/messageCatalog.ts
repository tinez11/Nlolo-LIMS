/**
 * What each message is, in a staff member's words (2026-11-08, the user: "not user feasible"). The keys are the
 * sender's; everything a person reads about a message -- its name, its topic, when it goes out, and the values a
 * preview shows -- lives here, so the screens never show OFFER_MADE or {{policyNumber}} unexplained.
 */

/**
 * The customer's journey (2026-11-08, the user: "onboarding, policies, savings, claims"): becoming a customer, the
 * policy's life, money saved or invested, then claims and money paid out.
 */
export type Topic = 'Onboarding' | 'Policies & premiums' | 'Savings & investments' | 'Claims & payouts';

export const TOPICS: readonly Topic[] = ['Onboarding', 'Policies & premiums', 'Savings & investments', 'Claims & payouts'];

interface Entry {
  name: string;
  topic: Topic;
  /** When the platform sends it -- the question an editor asks first. */
  when: string;
}

/**
 * In the journey's order, and within each stage by how often it goes out: a monthly payment receipt before a yearly
 * statement, an offer before its reminder. The order of this map IS the order of the screen.
 */
const CATALOG: Record<string, Entry> = {
  // Onboarding: from the offer to cover starting.
  OFFER_MADE: { name: 'Offer made', topic: 'Onboarding', when: 'When a proposal is accepted and the offer is waiting for its first premium.' },
  COVER_STARTED: { name: 'Cover started', topic: 'Onboarding', when: 'When the first premium clears and the policy comes on cover.' },
  OFFER_CLOSING: { name: 'Offer closing soon', topic: 'Onboarding', when: 'A few days before an unpaid offer closes.' },
  OFFER_EXPIRED: { name: 'Offer closed unpaid', topic: 'Onboarding', when: 'When an offer closes without its first premium.' },
  // Policies & premiums: what happens every month, then what changes now and then.
  PAYMENT_RECEIVED: { name: 'Payment received', topic: 'Policies & premiums', when: 'When a premium payment is confirmed and its receipt issued.' },
  AGENT_CASH_RECEIVED: { name: 'Cash collected by an agent', topic: 'Policies & premiums', when: 'When an agent records cash collected from the customer, before it reaches the bank.' },
  POLICY_LAPSED: { name: 'Policy lapsed', topic: 'Policies & premiums', when: 'When a policy lapses for unpaid premiums.' },
  POLICY_REINSTATED: { name: 'Policy reinstated', topic: 'Policies & premiums', when: 'When a lapsed policy is reinstated and cover resumes.' },
  FUNERAL_LIFE_ADDED: { name: 'Family member added', topic: 'Policies & premiums', when: 'When a life is added to a family funeral policy.' },
  FUNERAL_PREMIUM_CHANGED: { name: 'Funeral premium changed', topic: 'Policies & premiums', when: 'When adding or removing a life, or the yearly review, changes the premium.' },
  FUNERAL_LIFE_ENDED: { name: 'Family member removed', topic: 'Policies & premiums', when: 'When a life’s cover ends on a family funeral policy.' },
  // Savings & investments: money kept and grown.
  UNIT_LINKED_ALLOCATED: { name: 'Units bought', topic: 'Savings & investments', when: 'When the first premium buys fund units.' },
  ACCOUNT_STATEMENT: { name: 'Yearly savings statement', topic: 'Savings & investments', when: 'Once a year, after interest is credited to a savings account.' },
  UNIT_LINKED_STATEMENT: { name: 'Yearly fund statement', topic: 'Savings & investments', when: 'Once a year, with the value of the units.' },
  UNIT_LINKED_LOW_FUND: { name: 'Fund running low', topic: 'Savings & investments', when: 'When the fund will cover only a few more months of charges.' },
  UNIT_LINKED_LAPSED_EXHAUSTED: { name: 'Lapsed: fund used up', topic: 'Savings & investments', when: 'When the fund can no longer pay the charges and cover lapses.' },
  PENSION_VESTING_REMINDER: { name: 'Pension starting soon', topic: 'Savings & investments', when: 'Ahead of the date a pension starts paying, to choose how it is paid.' },
  // Claims & payouts: a claim's steps, then money paid out.
  CLAIM_RECEIVED: { name: 'Claim received', topic: 'Claims & payouts', when: 'When a claim is registered — sent to the claimant.' },
  CLAIM_APPROVED: { name: 'Claim approved', topic: 'Claims & payouts', when: 'When a claim is approved, with the amount — sent to the claimant.' },
  CLAIM_PAID: { name: 'Claim paid', topic: 'Claims & payouts', when: 'When a claim is paid — sent to the claimant.' },
  CLAIM_DECLINED: { name: 'Claim declined', topic: 'Claims & payouts', when: 'When a claim is declined — sent to the claimant, without the internal reason.' },
  BENEFIT_PAID: { name: 'Benefit paid', topic: 'Claims & payouts', when: 'When a one-off benefit is paid: maturity, survival, a premium return or a pension lump sum.' },
  UNIT_LINKED_PROCEEDS: { name: 'Units sold, payment coming', topic: 'Claims & payouts', when: 'When units are sold for a withdrawal, surrender or claim.' },
  SURRENDER_PAID: { name: 'Surrender paid', topic: 'Claims & payouts', when: 'When a surrender is paid and the policy ends.' },
  LOAN_PAID_OUT: { name: 'Loan paid out', topic: 'Claims & payouts', when: 'When a policy loan is paid out to the customer.' },
};

const ORDER = Object.keys(CATALOG);

/** Journey stage first, then the catalogue's order within it; a message not catalogued goes last. */
export function journeyOrder(a: string, b: string): number {
  const stage = (k: string) => {
    const t = CATALOG[k]?.topic;
    return t ? TOPICS.indexOf(t) : TOPICS.length;
  };
  const pos = (k: string) => (ORDER.includes(k) ? ORDER.indexOf(k) : ORDER.length);
  return stage(a) - stage(b) || pos(a) - pos(b) || a.localeCompare(b);
}

/** A readable name for any key, catalogued or not. */
export function messageName(key: string | undefined | null): string {
  if (!key) return '—';
  const entry = CATALOG[key];
  if (entry) return entry.name;
  const words = key.toLowerCase().replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function messageTopic(key: string): Topic | 'Other' {
  return CATALOG[key]?.topic ?? 'Other';
}

export function whenSent(key: string): string | null {
  return CATALOG[key]?.when ?? null;
}

/** What a preview fills each placeholder with: realistic, so the text reads as the customer will read it. */
const SAMPLES: Record<string, string> = {
  policyNumber: 'POL-3E440FE9',
  premium: 'TZS 20,000.00',
  amount: 'TZS 20,000.00',
  expiryDate: '15/12/2026',
  receiptNumber: 'RC-000123',
  year: '2026',
  balance: 'TZS 2,005,000.00',
  interest: 'TZS 105,000.00',
  fullName: 'Asha Juma',
  coverStart: '01/12/2026',
  endedOn: '30/11/2026',
  currency: 'TZS',
  effectiveFrom: '01/12/2026',
  vestingDate: '01/03/2027',
  allocated: '950,000.00',
  on: '30/11/2026',
  fundValue: '42,000.00',
  monthsCovered: '2',
  closingValue: '1,250,000.00',
  priceDate: '31/12/2026',
};

export function sampleFor(name: string): string {
  return SAMPLES[name] ?? `‹${name}›`;
}

export function samplesFor(names: readonly string[]): Record<string, string> {
  return Object.fromEntries(names.map((n) => [n, sampleFor(n)]));
}

/** The placeholders a body uses, in order of first use. */
export function placeholdersIn(body: string): string[] {
  const seen: string[] = [];
  for (const m of body.matchAll(/\{\{\s*(\w+)\s*\}\}/g)) {
    if (!seen.includes(m[1]!)) seen.push(m[1]!);
  }
  return seen;
}

export type Piece = { text: string; value?: false } | { text: string; value: true; name: string };

/** The body as the customer will read it, with each filled-in value marked so a preview can show where it came from. */
export function previewPieces(body: string): Piece[] {
  const pieces: Piece[] = [];
  let last = 0;
  for (const m of body.matchAll(/\{\{\s*(\w+)\s*\}\}/g)) {
    if (m.index! > last) pieces.push({ text: body.slice(last, m.index) });
    pieces.push({ text: sampleFor(m[1]!), value: true, name: m[1]! });
    last = m.index! + m[0].length;
  }
  if (last < body.length) pieces.push({ text: body.slice(last) });
  return pieces;
}

export function previewText(body: string): string {
  return previewPieces(body).map((p) => p.text).join('');
}

/** GSM-7: the plain alphabet an SMS carries 160 to a part; one character outside it drops a part to 70. */
const GSM7 = /^[A-Za-z0-9 @£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ!"#¤%&'()*+,\-./:;<=>?¡ÄÖÑÜ§¿äöñüà^{}\\[~\]|€]*$/;

/** How many SMS parts a text costs -- each billed as one message. */
export function smsParts(text: string): { characters: number; parts: number; unicode: boolean } {
  const unicode = !GSM7.test(text);
  const single = unicode ? 70 : 160;
  const multi = unicode ? 67 : 153;
  const characters = [...text].length;
  const parts = characters <= single ? 1 : Math.ceil(characters / multi);
  return { characters, parts, unicode };
}
