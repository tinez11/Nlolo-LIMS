import { useEffect, useState } from 'react';
import { getAnnuityTerms } from '@/api/annuity';
import type { AnnuityTermsView } from '@/api/types';

export type AnnuityTermsRead = { versionId: string; terms: AnnuityTermsView | null; error?: unknown };

/** The forms a version offers, read once per version. Null until read, or for a non-annuity version. */
export function useAnnuityTerms(productId: string, versionId: string): AnnuityTermsRead | null {
  const [loaded, setLoaded] = useState<AnnuityTermsRead | null>(null);
  useEffect(() => {
    if (!productId || !versionId) return;
    let live = true;
    getAnnuityTerms(productId, versionId).then(
      (terms) => live && setLoaded({ versionId, terms }),
      (error: unknown) => live && setLoaded({ versionId, terms: null, error }),
    );
    return () => {
      live = false;
    };
  }, [productId, versionId]);
  // Derived, not reset in the effect: a read for another version is no answer for this one.
  return loaded && loaded.versionId === versionId ? loaded : null;
}
