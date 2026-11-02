import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listGroupFuneralFamilies } from '@/api/groupFuneral';
import { Panel } from '@/components/Panel';
import { Button } from '@/components/ui/button';
import { formatMoney } from '@/lib/money';
import { selectScheme, usePolicyStore } from '@/store/policyStore';

/**
 * Who a scheme covers, on its policy's Overview (audit 2026-10-07): the overview showed only a total sum assured,
 * and the members were reachable only through a button in the page header. Members -- what an employer's or an
 * association's bill counts -- and, on a group funeral scheme, the lives in their families, with the way to the
 * full list.
 */
export function SchemeCoverPanel({ policyNumber, category, groupFuneral }: {
  policyNumber: string;
  category: string | null | undefined;
  groupFuneral: boolean;
}) {
  const scheme = usePolicyStore(selectScheme(policyNumber));
  const loadScheme = usePolicyStore((s) => s.loadScheme);
  const [lives, setLives] = useState<number | null>(null);

  useEffect(() => {
    void loadScheme(policyNumber);
  }, [policyNumber, loadScheme]);

  useEffect(() => {
    if (!groupFuneral) return undefined;
    let live = true;
    listGroupFuneralFamilies(policyNumber).then(
      (families) => {
        if (!live) return;
        setLives(families.filter((f) => f.status === 'ACTIVE')
          .reduce((n, f) => n + f.lives.filter((l) => l.status === 'ACTIVE').length, 0));
      },
      () => undefined,
    );
    return () => { live = false; };
  }, [policyNumber, groupFuneral]);

  const data = scheme.data;
  const creditLife = category === 'CREDIT_LIFE';
  const to = creditLife ? `/staff/credit-life-schemes/${encodeURIComponent(policyNumber)}`
    : `/staff/group-schemes/${encodeURIComponent(policyNumber)}`;
  const listName = creditLife ? 'borrowers and monthly files' : groupFuneral ? 'families' : 'member schedule';

  // One compact row (2026-11-02): the counts and the way to the list, so the policy's figures above stay in view.
  return (
    <Panel title="Who is covered">
      <div className="flex flex-wrap items-center gap-x-8 gap-y-2 px-4 pb-3">
        <dl className="flex flex-wrap items-baseline gap-x-8 gap-y-1 text-sm">
          <div className="flex items-baseline gap-2">
            <dt className="text-xs text-muted-foreground">{creditLife ? 'Borrowers' : 'Members'}</dt>
            <dd className="font-semibold tabular-nums">{data?.activeMemberCount ?? '—'}</dd>
          </div>
          {groupFuneral && (
            <div className="flex items-baseline gap-2">
              <dt className="text-xs text-muted-foreground">Lives</dt>
              <dd className="font-semibold tabular-nums">{lives ?? '—'}</dd>
            </div>
          )}
          <div className="flex items-baseline gap-2">
            <dt className="text-xs text-muted-foreground">Total covered</dt>
            <dd className="font-semibold tabular-nums">{data ? formatMoney(data.totalCovered) : '—'}</dd>
          </div>
        </dl>
        <Button asChild size="sm" variant="outline" className="ml-auto">
          <Link to={to}>Open the {listName}</Link>
        </Button>
      </div>
    </Panel>
  );
}
