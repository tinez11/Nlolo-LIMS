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

  return (
    <Panel title="Who is covered" subtitle={creditLife ? "The lender's borrowers, enrolled file by file"
      : groupFuneral ? "The association's members and their families" : "The employer's members"}>
      <dl className="grid grid-cols-2 gap-x-6 gap-y-2 px-4 py-3 text-sm sm:grid-cols-3">
        <div>
          <dt className="text-xs text-muted-foreground">{creditLife ? 'Borrowers covered' : 'Members'}</dt>
          <dd className="text-title">{data?.activeMemberCount ?? '—'}</dd>
        </div>
        {groupFuneral && (
          <div>
            <dt className="text-xs text-muted-foreground">Lives (members and their families)</dt>
            <dd className="text-title">{lives ?? '—'}</dd>
          </div>
        )}
        <div>
          <dt className="text-xs text-muted-foreground">Total covered</dt>
          <dd className="text-title">{data ? formatMoney(data.totalCovered) : '—'}</dd>
        </div>
      </dl>
      <div className="px-4 pb-3">
        <Button asChild size="sm" variant="ghost" className="-ml-2">
          <Link to={to}>Open the {listName}</Link>
        </Button>
      </div>
    </Panel>
  );
}
