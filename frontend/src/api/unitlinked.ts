import { get, post, put } from '@/lib/http';
import type { ApiError } from '@/lib/apiError';
import type { MutationAttempt } from '@/lib/idempotency';
import type {
  CreateFundRequest,
  FundPriceView,
  FundShare,
  FundView,
  PolicyUnitsView,
  PremiumSplitView,
  PriceAdjustmentView,
  ProposePriceRequest,
  SwitchRequestBody,
  SwitchView,
  UnitLinkedChoiceView,
  UnitLinkedReconciliationView,
  UnitLinkedTermsView,
  UnitLinkedTopUpRequest,
  UnitLinkedTopUpView,
  UnitLinkedWithdrawalView,
  UnitStatementView,
  WaitingCountView,
  WithdrawalRequestBody,
} from './types';

/**
 * Unit-linked (product step 6): the fund register and its two-person prices, a version's terms, a
 * case's fund split, and a policy's units. A 404 on the terms or the choice is an answer -- not a
 * unit-linked version or case -- normalised to null, as the annuity calls do.
 */

function isNotFound(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as ApiError).kind === 'notFound';
}

async function orNull<T>(load: () => Promise<T>): Promise<T | null> {
  try {
    return await load();
  } catch (error) {
    if (isNotFound(error)) return null;
    throw error;
  }
}

const enc = encodeURIComponent;

export function listFunds(): Promise<FundView[]> {
  return get<FundView[]>('/funds');
}

export function createFund(body: CreateFundRequest): Promise<FundView> {
  return post<FundView>('/funds', body);
}

export function closeFund(code: string): Promise<FundView> {
  return post<FundView>(`/funds/${enc(code)}/closure`);
}

export function listFundPrices(code: string): Promise<FundPriceView[]> {
  return get<FundPriceView[]>(`/funds/${enc(code)}/prices`);
}

export function listWaiting(code: string): Promise<WaitingCountView[]> {
  return get<WaitingCountView[]>(`/funds/${enc(code)}/waiting`);
}

export function proposePrice(body: ProposePriceRequest): Promise<FundPriceView> {
  return post<FundPriceView>('/fund-prices', body);
}

export function approvePrice(priceId: string): Promise<FundPriceView> {
  return post<FundPriceView>(`/fund-prices/${enc(priceId)}/approval`);
}

export function withdrawPrice(priceId: string): Promise<FundPriceView> {
  return post<FundPriceView>(`/fund-prices/${enc(priceId)}/withdrawal`);
}

export function proposeCorrection(priceId: string, price: string, reason: string): Promise<FundPriceView> {
  return post<FundPriceView>(`/fund-prices/${enc(priceId)}/correction`, { price, reason });
}

export function listAdjustments(status?: string): Promise<PriceAdjustmentView[]> {
  return get<PriceAdjustmentView[]>(status ? `/price-adjustments?status=${enc(status)}` : '/price-adjustments');
}

export function settleAdjustment(adjustmentId: string, payeeRef: string): Promise<PriceAdjustmentView> {
  return post<PriceAdjustmentView>(`/price-adjustments/${enc(adjustmentId)}/settlement`, { payeeRef });
}

export function waiveAdjustment(adjustmentId: string, reason: string): Promise<PriceAdjustmentView> {
  return post<PriceAdjustmentView>(`/price-adjustments/${enc(adjustmentId)}/waiver`, { reason });
}

export function getPolicyUnits(policyNumber: string): Promise<PolicyUnitsView> {
  return get<PolicyUnitsView>(`/policies/${enc(policyNumber)}/units`);
}

export function namePayee(policyNumber: string, payeeRef: string): Promise<void> {
  return post<void>(`/policies/${enc(policyNumber)}/units/payee`, { payeeRef });
}

/** A version's unit-linked terms; null for any other version. */
export function getUnitLinkedTerms(productId: string, versionId: string): Promise<UnitLinkedTermsView | null> {
  return orNull(() => get<UnitLinkedTermsView>(`/products/${enc(productId)}/versions/${enc(versionId)}/unit-linked`));
}

/** A case's fund split, premium and sum assured; null for a case that has none. */
export function getUnitLinkedChoice(caseId: string): Promise<UnitLinkedChoiceView | null> {
  return orNull(() => get<UnitLinkedChoiceView>(`/underwriting/cases/${enc(caseId)}/unit-linked-choice`));
}

export function recordUnitLinkedChoice(caseId: string, body: UnitLinkedChoiceView): Promise<UnitLinkedChoiceView> {
  return put<UnitLinkedChoiceView>(`/underwriting/cases/${enc(caseId)}/unit-linked-choice`, body);
}

export function getUnitLinkedReconciliation(): Promise<UnitLinkedReconciliationView> {
  return get<UnitLinkedReconciliationView>('/finance/unit-linked-reconciliation');
}

// ---- U2: the premium split, switches, withdrawals, top-ups and statements ----

export function getSplitHistory(policyNumber: string): Promise<PremiumSplitView[]> {
  return get<PremiumSplitView[]>(`/policies/${enc(policyNumber)}/premium-split`);
}

export function redirectPremiums(policyNumber: string, split: FundShare[]): Promise<PremiumSplitView> {
  return put<PremiumSplitView>(`/policies/${enc(policyNumber)}/premium-split`, { split });
}

export function listSwitches(policyNumber: string): Promise<SwitchView[]> {
  return get<SwitchView[]>(`/policies/${enc(policyNumber)}/switches`);
}

export function requestSwitch(policyNumber: string, body: SwitchRequestBody): Promise<SwitchView> {
  return post<SwitchView>(`/policies/${enc(policyNumber)}/switches`, body);
}

export function listWithdrawals(policyNumber: string): Promise<UnitLinkedWithdrawalView[]> {
  return get<UnitLinkedWithdrawalView[]>(`/policies/${enc(policyNumber)}/withdrawals`);
}

export function requestWithdrawal(policyNumber: string, body: WithdrawalRequestBody): Promise<UnitLinkedWithdrawalView> {
  return post<UnitLinkedWithdrawalView>(`/policies/${enc(policyNumber)}/withdrawals`, body);
}

export function approveWithdrawal(withdrawalId: string): Promise<UnitLinkedWithdrawalView> {
  return post<UnitLinkedWithdrawalView>(`/withdrawals/${enc(withdrawalId)}/approval`);
}

export function listTopUps(policyNumber: string): Promise<UnitLinkedTopUpView[]> {
  return get<UnitLinkedTopUpView[]>(`/policies/${enc(policyNumber)}/top-ups`);
}

/** Once per attempt: the same attempt's key on a retry answers with the first top-up and collects nothing again. */
export function requestTopUp(
  policyNumber: string,
  body: UnitLinkedTopUpRequest,
  attempt: MutationAttempt,
): Promise<UnitLinkedTopUpView> {
  return post<UnitLinkedTopUpView>(`/policies/${enc(policyNumber)}/top-ups`, body, { headers: attempt.headers() });
}

export function listStatements(policyNumber: string): Promise<UnitStatementView[]> {
  return get<UnitStatementView[]>(`/policies/${enc(policyNumber)}/statements`);
}

export function fileStatement(policyNumber: string, from: string, to: string): Promise<UnitStatementView> {
  return post<UnitStatementView>(`/policies/${enc(policyNumber)}/statements`, { from, to });
}

export function downloadUnitStatement(statementId: string): Promise<Blob> {
  return get<Blob>(`/unit-statements/${enc(statementId)}/file`, { responseType: 'blob' });
}
