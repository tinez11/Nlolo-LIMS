import type { components as ProductComponents } from '@/types/api/product';

/**
 * The IFRS 17 classification vocabulary the console shows (IFRS 17 I2): portfolios, expected profitability,
 * measurement models and sales channels, in the words a finance officer or an actuary uses. The codes are the wire's;
 * a code this build has never heard of falls back to itself rather than to a guess.
 */

export type PortfolioCode = ProductComponents['schemas']['PortfolioCode'];
export type ProfitabilityBucket = ProductComponents['schemas']['ProfitabilityBucket'];
export type Ifrs17Model = ProductComponents['schemas']['Ifrs17Model'];
type ProductCategory = ProductComponents['schemas']['CreateProductRequest']['category'];

export const PORTFOLIO_LABEL: Record<PortfolioCode, string> = {
  TERM: 'Term life',
  WL: 'Whole life',
  END: 'Endowment',
  MB: 'Money-back',
  PAR: 'With-profits',
  ULIP: 'Unit-linked',
  SAV: 'Savings account',
  DEP: 'Fixed-term deposit',
  IANN: 'Immediate annuity',
  DANN: 'Deferred annuity',
  PEN: 'Pension',
  GRPL: 'Group life',
  CRL: 'Credit life',
  FUN: 'Family funeral',
};

export const PORTFOLIO_CODES = Object.keys(PORTFOLIO_LABEL) as PortfolioCode[];

/**
 * The portfolio a product of this category takes unless its author says otherwise -- PortfolioCode.defaultFor on the
 * server. A with-profits endowment, a savings plan or a pension names its narrower portfolio by hand.
 */
export function portfolioDefaultFor(category: ProductCategory): PortfolioCode {
  switch (category) {
    case 'TERM_LIFE':
      return 'TERM';
    case 'ENDOWMENT':
    case 'EDUCATION_SAVINGS':
      return 'END';
    case 'WHOLE_LIFE':
      return 'WL';
    case 'ANNUITY':
      return 'IANN';
    case 'UNIT_LINKED':
      return 'ULIP';
    case 'GROUP_LIFE':
      return 'GRPL';
    case 'CREDIT_LIFE':
      return 'CRL';
    case 'FUNERAL':
      return 'FUN';
  }
}

export const BUCKET_LABEL: Record<ProfitabilityBucket, string> = {
  ONEROUS: 'Onerous',
  NO_SIGNIFICANT_RISK: 'No significant risk of becoming onerous',
  REMAINING: 'Remaining',
};

export const MODEL_LABEL: Record<Ifrs17Model, string> = {
  GMM: 'General model (GMM)',
  VFA: 'Variable fee approach (VFA)',
  PAA: 'Premium allocation approach (PAA)',
  IFRS9: 'IFRS 9 (not insurance)',
};

export const CHANNEL_LABEL: Record<string, string> = {
  AGENT: 'Tied agent',
  BROKER: 'Broker',
  BANCASSURANCE: 'Bancassurance',
  DIRECT: 'Direct',
  DIGITAL: 'Digital',
};

export function portfolioLabel(code: string | null | undefined): string {
  return code ? (PORTFOLIO_LABEL[code as PortfolioCode] ?? code) : '—';
}

export function bucketLabel(code: string | null | undefined): string {
  return code ? (BUCKET_LABEL[code as ProfitabilityBucket] ?? code) : '—';
}

export function modelLabel(code: string | null | undefined): string {
  return code ? (MODEL_LABEL[code as Ifrs17Model] ?? code) : '—';
}

export function channelLabel(code: string | null | undefined): string {
  return code ? (CHANNEL_LABEL[code] ?? code) : '—';
}
