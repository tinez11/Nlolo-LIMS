import Link from 'next/link';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { MoneyText } from '@/components/money';

type Money = { amount: string; currencyCode: string };
export type PolicySummary = {
  policyNumber: string;
  status: string;
  issueDate: string;
  sumAssured: Money;
  premium: Money;
  premiumFrequency: string;
  cashValue: Money;
};

export function PolicyList({ policies }: { policies: PolicySummary[] }) {
  if (policies.length === 0) {
    return <p className="text-muted-foreground">No policies to show yet.</p>;
  }
  return (
    <div className="grid gap-4 md:grid-cols-2">
      {policies.map((policy) => (
        <Card key={policy.policyNumber}>
          <CardHeader className="flex flex-row items-center justify-between">
            <CardTitle>
              <Link href={`/policies/${policy.policyNumber}`}>{policy.policyNumber}</Link>
            </CardTitle>
            <Badge>{policy.status}</Badge>
          </CardHeader>
          <CardContent className="space-y-1 text-sm">
            <div>Sum assured: <MoneyText {...policy.sumAssured} /></div>
            <div>Premium: <MoneyText {...policy.premium} /> / {policy.premiumFrequency.toLowerCase()}</div>
            <div>Cash value: <MoneyText {...policy.cashValue} /></div>
            <div className="text-muted-foreground">Issued {policy.issueDate}</div>
          </CardContent>
        </Card>
      ))}
    </div>
  );
}
