import type { PolicyView } from '@/api/types';
import { humanizeStatus } from '@/lib/status';
import { cn } from '@/lib/cn';
import { ProductName } from './ProductName';

/**
 * What a policy was sold as: its product's name, and the kind of product (2026-10-22, the user -- a list of policy
 * numbers and statuses did not say which was the funeral plan and which the group scheme). The name is looked up
 * once per product and cached; the kind comes with the policy.
 */
export function PolicyProduct({ policy, className }: { policy: Pick<PolicyView, 'productId' | 'productCategory'>; className?: string }) {
  const kind = policy.productCategory ? humanizeStatus(policy.productCategory) : null;
  if (!policy.productId && !kind) return null;
  return (
    <span className={cn('text-xs text-muted-foreground', className)}>
      {policy.productId && <ProductName productId={policy.productId} withCode={false} className="text-[length:1em] text-foreground" />}
      {policy.productId && kind && <span aria-hidden> · </span>}
      {kind && <span className="whitespace-nowrap">{kind}</span>}
    </span>
  );
}
