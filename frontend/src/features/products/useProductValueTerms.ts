import { useEffect, useState } from 'react';
import { getValueTerms, listProductVersions } from '@/api/products';
import type { VersionValueTermsView } from '@/api/types';

/**
 * Every version's value terms for one product (2026-10-29), or null until they arrive: what decides whether the page
 * offers bonus and rate declarations, and what kind of product it is. A failed read stays null, so the panels it
 * gates stay hidden rather than offered on a guess.
 */
export function useProductValueTerms(productId: string, refreshKey: number): VersionValueTermsView[] | null {
  const [loaded, setLoaded] = useState<{ key: string; value: VersionValueTermsView[] } | null>(null);
  const key = `${productId}:${refreshKey}`;
  useEffect(() => {
    let live = true;
    listProductVersions(productId)
      .then((versions) => Promise.all(versions.map((v) => getValueTerms(productId, v.productVersionId))))
      .then((value) => { if (live) setLoaded({ key, value }); }, () => undefined);
    return () => { live = false; };
  }, [productId, key]);
  return loaded?.key === key ? loaded.value : null;
}
