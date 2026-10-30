import { create } from 'zustand';
import type { CustomerApplication, CustomerProduct, CustomerQuote, CustomerQuoteRequest } from '@/api/portal';

/**
 * The customer's self-service flow (2026-10-29, the user chose "start from what you need, then one question at a
 * time"): which kinds of product are priced here, the questions each asks, and where an application stands. Every price
 * is the server's; nothing here adds anything up.
 */

/** The needs a customer starts from, in the order the tiles show them -- one per kind priced online. */
export const NEEDS = ['PROTECTION', 'CASH_VALUE', 'SAVINGS_ACCOUNT', 'FUNERAL', 'DEPOSIT'] as const;
export type Need = (typeof NEEDS)[number];

export function isNeed(kind: string): kind is Need {
  return (NEEDS as readonly string[]).includes(kind);
}

export type Step = 'saveHow' | 'amount' | 'years' | 'frequency' | 'term' | 'plan' | 'family';

/**
 * The questions a product asks before its price, one screen each. A savings account asks how often only when the
 * customer chose to save a fixed amount regularly (2026-10-29: one deposit to open it is the default).
 */
export function stepsFor(product: CustomerProduct, answers: Answers = {}): Step[] {
  switch (product.kind) {
    case 'SAVINGS_ACCOUNT': return [
      'saveHow', 'amount', ...(answers.saveHow === 'REGULAR' ? ['frequency' as const] : []),
      ...(yearChoices(product).length > 0 ? ['years' as const] : []),
    ];
    case 'DEPOSIT': return ['amount', 'term'];
    case 'FUNERAL': return ['plan', 'family', 'frequency'];
    default: return yearChoices(product).length > 0 ? ['amount', 'years', 'frequency'] : ['amount', 'frequency'];
  }
}

const COVER = [5_000_000, 10_000_000, 20_000_000, 50_000_000];
const SAVING = [2_000_000, 5_000_000, 10_000_000, 20_000_000];
const DEPOSITS = [1_000_000, 5_000_000, 10_000_000, 20_000_000];
const SAVINGS = [50_000, 100_000, 500_000, 1_000_000];

/** A few round amounts inside what the product takes; "another amount" covers the rest. */
export function amountChoices(product: CustomerProduct): number[] {
  const base = product.kind === 'DEPOSIT' ? DEPOSITS : product.kind === 'SAVINGS_ACCOUNT' ? SAVINGS
    : product.kind === 'CASH_VALUE' ? SAVING : COVER;
  const min = product.terms.minAmount ?? 0;
  const max = product.terms.maxAmount ?? Number.POSITIVE_INFINITY;
  return base.filter((a) => a >= min && a <= max);
}

/** Whole-year terms the version allows; none where it sets no term at all. */
export function yearChoices(product: CustomerProduct): number[] {
  const { minTermYears, maxTermYears } = product.terms;
  if (minTermYears == null && maxTermYears == null) return [];
  const min = minTermYears ?? 1;
  const max = maxTermYears ?? 40;
  const picks = [5, 10, 15, 20, 25, 30].filter((y) => y >= min && y <= max);
  if (picks.length > 0) return picks;
  return min === max ? [min] : [min, max];
}

/** What the customer has answered so far. */
export interface Answers {
  /** A savings account: one deposit to open it, or a fixed amount regularly. */
  saveHow?: 'ONCE' | 'REGULAR' | undefined;
  amount?: number | undefined;
  years?: number | undefined;
  frequency?: 'MONTHLY' | 'QUARTERLY' | 'ANNUALLY' | undefined;
  termMonths?: number | undefined;
  planCode?: string | undefined;
  family?: FamilyMember[] | undefined;
}

export interface FamilyMember {
  role: 'SPOUSE' | 'CHILD' | 'PARENT' | 'EXTENDED';
  name: string;
  dateOfBirth: string;
  student: boolean;
}

/** Whether a step's answer is there, so Next can be offered. */
export function answered(step: Step, a: Answers): boolean {
  switch (step) {
    case 'saveHow': return a.saveHow != null;
    case 'amount': return a.amount != null && a.amount > 0;
    case 'years': return a.years != null;
    case 'frequency': return a.frequency != null;
    case 'term': return a.termMonths != null;
    case 'plan': return a.planCode != null;
    case 'family': return (a.family ?? []).every((m) => m.name.trim() !== '' && m.dateOfBirth !== '');
  }
}

/** The quote request for a product's kind, from the answers. */
export function quoteRequest(product: CustomerProduct, a: Answers): CustomerQuoteRequest {
  switch (product.kind) {
    case 'DEPOSIT':
      return { amount: a.amount ?? null, termMonths: a.termMonths ?? null };
    case 'SAVINGS_ACCOUNT':
      // No frequency is one deposit: the server reads it as SINGLE.
      return { amount: a.amount ?? null, frequency: a.saveHow === 'REGULAR' ? a.frequency ?? 'MONTHLY' : null, termYears: a.years ?? null };
    case 'FUNERAL':
      return {
        planCode: a.planCode ?? null, frequency: a.frequency ?? 'MONTHLY',
        family: (a.family ?? []).map((m) => ({ role: m.role, name: m.name.trim(), dateOfBirth: m.dateOfBirth, student: m.student })),
      };
    default:
      return { sumAssured: a.amount ?? null, frequency: a.frequency ?? 'MONTHLY', termYears: a.years ?? null };
  }
}

/** The quote chosen, carried from its price to the application form. */
interface Draft {
  product: CustomerProduct;
  request: CustomerQuoteRequest;
  /** Null when the price was refused here and an adviser will work it out. */
  quote: CustomerQuote | null;
}

export const useApplyDraft = create<{ draft: Draft | null; keep: (draft: Draft) => void; clear: () => void }>((set) => ({
  draft: null,
  keep: (draft) => set({ draft }),
  clear: () => set({ draft: null }),
}));

/**
 * Where an application sits on the five-step line: Sent, Being reviewed, Decision, Pay first premium, Cover starts.
 * `at` is the step in progress; `stopped` ends the line at the decision for a postponement or a decline.
 */
export function progressOf(application: Pick<CustomerApplication, 'stage'>): { at: number; tone: 'go' | 'wait' | 'stop' } {
  switch (application.stage) {
    case 'NEEDS_YOU': return { at: 1, tone: 'wait' };
    case 'ACCEPTED':
    case 'ISSUED': return { at: 3, tone: 'go' };
    case 'POSTPONED': return { at: 2, tone: 'wait' };
    case 'DECLINED': return { at: 2, tone: 'stop' };
    default: return { at: 1, tone: 'go' };
  }
}

export interface Person { name: string; relationship: string; phone: string; share: string }
export interface Answer { answer: 'YES' | 'NO' | null; notes: string }

/** GOOD_HEALTH's worrying answer is No; every other question's is Yes. The server holds the same rule. */
export function needsDetail(code: string, answer: 'YES' | 'NO' | null): boolean {
  if (answer == null) return false;
  return code === 'GOOD_HEALTH' ? answer === 'NO' : answer === 'YES';
}

/** What is wrong with the people named, or null: rows left blank are dropped, the rest must share 100%. */
export function beneficiaryProblem(people: Person[]): { total: number } | null {
  const named = people.filter((p) => p.name.trim() !== '');
  if (named.length === 0) return null;
  const total = named.reduce((sum, p) => sum + (Number(p.share) || 0), 0);
  return Math.abs(total - 100) < 0.001 ? null : { total };
}

