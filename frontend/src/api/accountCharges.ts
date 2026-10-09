import { get, post, put } from '@/lib/http';
import type { components as ProductComponents } from '@/types/api/product';

/**
 * Account charges (2026-10-09, product V32): staff create them -- flat or a percentage, and when each is taken -- and
 * choose which a savings policy is charged by, on its case or on the manual issue screen.
 */

export type AccountChargeView = ProductComponents['schemas']['AccountCharge'];
export type ChargeWhen = AccountChargeView['when'];
export type ChargeAmountType = AccountChargeView['amountType'];

const enc = encodeURIComponent;

export function listAccountCharges(activeOnly = false): Promise<AccountChargeView[]> {
  return get<AccountChargeView[]>('/account-charges', { params: { activeOnly } });
}

export function createAccountCharge(charge: {
  name: string; description: string | null; when: ChargeWhen; amountType: ChargeAmountType; amount: number; currency: string;
}): Promise<AccountChargeView> {
  return post<AccountChargeView>('/account-charges', charge);
}

export function withdrawAccountCharge(chargeId: string): Promise<AccountChargeView> {
  return post<AccountChargeView>(`/account-charges/${enc(chargeId)}/withdrawal`, {});
}

export function reinstateAccountCharge(chargeId: string): Promise<AccountChargeView> {
  return post<AccountChargeView>(`/account-charges/${enc(chargeId)}/reinstatement`, {});
}

/** The organisation's rule: may a charge take an account below its product's minimum balance? */
export function getChargeSetting(): Promise<{ mayGoBelowMinimum: boolean }> {
  return get<{ mayGoBelowMinimum: boolean }>('/account-charges/setting');
}

export function setChargeSetting(mayGoBelowMinimum: boolean): Promise<{ mayGoBelowMinimum: boolean }> {
  return put<{ mayGoBelowMinimum: boolean }>('/account-charges/setting', { mayGoBelowMinimum });
}

/** The charges chosen on a savings case; empty for the product's own. */
export function getCaseAccountCharges(caseId: string): Promise<{ chargeIds: string[] }> {
  return get<{ chargeIds: string[] }>(`/underwriting/cases/${enc(caseId)}/account-charges`);
}

export function setCaseAccountCharges(caseId: string, chargeIds: string[]): Promise<{ chargeIds: string[] }> {
  return put<{ chargeIds: string[] }>(`/underwriting/cases/${enc(caseId)}/account-charges`, { chargeIds });
}

/** The charges a savings policy is charged by; empty for its product's own. */
export function getPolicyAccountCharges(policyNumber: string): Promise<AccountChargeView[]> {
  return get<AccountChargeView[]>(`/policies/${enc(policyNumber)}/account-charges`);
}

/** Correct a draft product's portfolio -- a savings product must be SAV, DEP or PEN/DANN to publish. */
export function changeProductPortfolio(productId: string, portfolioCode: string): Promise<unknown> {
  return put<unknown>(`/products/${enc(productId)}/portfolio`, { portfolioCode });
}
