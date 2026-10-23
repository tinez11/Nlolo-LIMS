import type { AnnuityQuoteView, FuneralQuoteView, PremiumQuoteView, SavingsQuoteView } from '@/api/types';

/**
 * The server's answer to a quotation, handed up from the panel that asked for it (2026-10-23), so the page can draw it
 * over time and price alternatives beside it. Exactly what the server sent -- never a figure worked out here.
 */
export type QuoteResult =
  | { kind: 'premium'; quote: PremiumQuoteView; sumAssured: string; currency: string; termMonths: number | null }
  | { kind: 'savings'; quote: SavingsQuoteView }
  | { kind: 'funeral'; quote: FuneralQuoteView }
  | { kind: 'annuity'; quote: AnnuityQuoteView; purchasePrice: string };
