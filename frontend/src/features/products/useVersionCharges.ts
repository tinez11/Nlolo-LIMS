import { useEffect, useState } from 'react';
import { getVersionAccountCharges, type AccountChargeView } from '@/api/accountCharges';

/**
 * The Account charges a product version takes (2026-10-22, V34) -- what a policy on it is charged by when none are
 * chosen for it. Null while loading, or with no version to ask about; a failed read is treated as unknown (null).
 */
export function useVersionCharges(productId?: string | undefined, versionId?: string | undefined): AccountChargeView[] | null {
  const [loaded, setLoaded] = useState<{ key: string; charges: AccountChargeView[] } | null>(null);
  const key = productId && versionId ? `${productId}/${versionId}` : '';

  useEffect(() => {
    if (!productId || !versionId) return undefined;
    let live = true;
    getVersionAccountCharges(productId, versionId).then(
      (charges) => { if (live) setLoaded({ key: `${productId}/${versionId}`, charges }); },
      () => undefined,
    );
    return () => { live = false; };
  }, [productId, versionId]);

  return key && loaded?.key === key ? loaded.charges : null;
}
