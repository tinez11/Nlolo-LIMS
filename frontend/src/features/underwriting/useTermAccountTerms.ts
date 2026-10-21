import { useEffect, useState } from 'react';
import { getTermAccountTerms } from '@/api/products';
import type { TermAccountTermsView } from '@/api/types';

/**
 * Whether the chosen version is a term account, and its rate band (2026-10-21) -- for every screen that proposes or
 * quotes one: the case form, the agents' calculator and the agent's proposal. Asked only of a savings (SAV) product,
 * because every term account is one; null for anything else, and while the answer is on its way.
 */
export function useTermAccountTerms(
  productId: string | null | undefined,
  productVersionId: string | null | undefined,
  portfolioCode: string | null | undefined,
): TermAccountTermsView | null {
  const ask = portfolioCode === 'SAV' && !!productId && !!productVersionId;
  const key = ask ? `${productId}|${productVersionId}` : '';
  const [loaded, setLoaded] = useState<{ key: string; terms: TermAccountTermsView } | null>(null);

  useEffect(() => {
    if (!key || !productId || !productVersionId) return undefined;
    let live = true;
    getTermAccountTerms(productId, productVersionId).then(
      (terms) => live && setLoaded({ key, terms }),
      () => undefined,
    );
    return () => {
      live = false;
    };
  }, [key, productId, productVersionId]);

  return loaded && loaded.key === key && loaded.terms.termAccount ? loaded.terms : null;
}
