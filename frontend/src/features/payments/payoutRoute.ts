import { z } from 'zod';

/**
 * How a payout reaches the person (2026-10-28, user decisions): mobile money, a bank transfer or a cheque -- never
 * cash -- with a bank account as proper fields rather than one free-text "destination". Mirrors PayoutRoute on the
 * server, which checks the same things in the same words.
 */
export type PayoutMethod = 'MOBILE_MONEY' | 'EFT' | 'CHEQUE';

export const PAYOUT_METHODS: { value: PayoutMethod; label: string; hint: string }[] = [
  { value: 'MOBILE_MONEY', label: 'Mobile money', hint: 'Sent to the number by the mobile-money provider once approved.' },
  { value: 'EFT', label: 'Bank transfer', hint: 'Finance makes the transfer in the bank and records its reference.' },
  { value: 'CHEQUE', label: 'Cheque', hint: 'Finance writes the cheque and records its number.' },
];

/**
 * Banks licensed in Tanzania, offered as suggestions -- the field still takes any name, so a bank missing here is
 * typed rather than refused.
 */
export const TANZANIAN_BANKS = [
  'CRDB Bank', 'NMB Bank', 'NBC Bank', 'Stanbic Bank', 'Absa Bank Tanzania', 'Equity Bank', 'Exim Bank',
  'Diamond Trust Bank (DTB)', 'KCB Bank', 'Azania Bank', 'Tanzania Commercial Bank (TCB)', 'Amana Bank',
  'Bank of Africa', 'Standard Chartered', 'I&M Bank', 'Access Bank', 'Akiba Commercial Bank', 'Mkombozi Bank',
  'Letshego Bank', 'Maendeleo Bank', 'Mwanga Hakika Bank', 'UBA Tanzania', 'Citibank Tanzania', 'Ecobank',
  'BancABC', 'Habib African Bank', 'China Dasheng Bank', 'Guaranty Trust Bank', 'People’s Bank of Zanzibar (PBZ)',
];

export const payoutRouteFields = {
  method: z.enum(['MOBILE_MONEY', 'EFT', 'CHEQUE']),
  payeeRef: z.string().trim().max(200),
  payeeBank: z.string().trim().max(100),
  payeeBranch: z.string().trim().max(100),
  payeeAccountName: z.string().trim().max(200),
  payeeAccountNumber: z.string().trim().max(50),
};

export type PayoutRouteValues = {
  method: PayoutMethod;
  payeeRef: string;
  payeeBank: string;
  payeeBranch: string;
  payeeAccountName: string;
  payeeAccountNumber: string;
};

export const BLANK_ROUTE: PayoutRouteValues = {
  method: 'MOBILE_MONEY',
  payeeRef: '',
  payeeBank: '',
  payeeBranch: '',
  payeeAccountName: '',
  payeeAccountNumber: '',
};

/** The per-method checks, for a schema's superRefine: each missing field is named on that field. */
export function refineRoute(v: PayoutRouteValues, ctx: z.RefinementCtx): void {
  const need = (path: keyof PayoutRouteValues, message: string) => {
    if (!v[path].trim()) ctx.addIssue({ code: 'custom', path: [path], message });
  };
  if (v.method === 'MOBILE_MONEY') need('payeeRef', 'Give the mobile-money number to pay');
  if (v.method === 'CHEQUE') need('payeeRef', 'Give the name the cheque is made out to');
  if (v.method === 'EFT') {
    need('payeeBank', 'Give the bank to transfer to');
    need('payeeAccountName', 'Give the name on the bank account');
    need('payeeAccountNumber', 'Give the bank account number');
  }
}

/** The request fields for the chosen method only, trimmed -- a number typed before switching to a bank is not sent. */
export function routeBody(v: PayoutRouteValues): {
  method: PayoutMethod;
  payeeRef?: string;
  payeeBank?: string;
  payeeBranch?: string;
  payeeAccountName?: string;
  payeeAccountNumber?: string;
} {
  if (v.method === 'EFT') {
    return {
      method: 'EFT',
      payeeBank: v.payeeBank.trim(),
      ...(v.payeeBranch.trim() ? { payeeBranch: v.payeeBranch.trim() } : {}),
      payeeAccountName: v.payeeAccountName.trim(),
      payeeAccountNumber: v.payeeAccountNumber.trim(),
    };
  }
  return { method: v.method, payeeRef: v.payeeRef.trim() };
}

interface RouteLike {
  method?: string | null;
  payeeRef?: string | null;
  payeeBank?: string | null;
  payeeBranch?: string | null;
  payeeAccountName?: string | null;
  payeeAccountNumber?: string | null;
}

/** One line for a row: "+255712…", "CRDB Bank, Kariakoo · Amina Owner · 0150…", "Cheque to Amina Owner". */
export function describeRoute(r: RouteLike): string {
  if (r.method === 'EFT') {
    const bank = [r.payeeBank, r.payeeBranch].filter(Boolean).join(', ');
    return [bank, r.payeeAccountName, r.payeeAccountNumber].filter(Boolean).join(' · ');
  }
  if (r.method === 'CHEQUE') return `Cheque to ${r.payeeRef ?? '—'}`;
  return r.payeeRef ?? '—';
}
