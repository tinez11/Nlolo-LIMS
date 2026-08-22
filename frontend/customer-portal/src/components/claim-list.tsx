import Link from 'next/link';
import { Badge } from '@/components/ui/badge';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table';
import { MoneyText } from '@/components/money';

type Money = { amount: string; currencyCode: string };
export type ClaimType = 'DEATH' | 'DISABILITY' | 'CRITICAL_ILLNESS' | 'MATURITY';
export type ClaimStatus =
  | 'REGISTERED' | 'UNDER_ASSESSMENT' | 'APPROVED' | 'REJECTED'
  | 'SETTLEMENT_REQUESTED' | 'SETTLED' | 'REOPENED';

export type ClaimSummary = {
  claimId: string;
  policyNumber: string;
  claimType: ClaimType;
  status: ClaimStatus;
  dateOfEvent: string;
  approvedAmount?: Money;
  /**
   * Re-derived server-side on every read; an internal assessment/scrutiny signal, not a
   * customer-facing verdict. Accepted here only so callers can pass a real ClaimView straight
   * through -- deliberately never read or rendered below.
   */
  requiresContestabilityReview: boolean;
};

function titleCase(value: string): string {
  return value
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}

export function ClaimList({ claims }: { claims: ClaimSummary[] }) {
  if (claims.length === 0) {
    return <p className="text-muted-foreground">No claims to show yet.</p>;
  }

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Date of event</TableHead>
          <TableHead>Type</TableHead>
          <TableHead>Status</TableHead>
          <TableHead>Approved amount</TableHead>
          <TableHead />
        </TableRow>
      </TableHeader>
      <TableBody>
        {claims.map((claim) => (
          <TableRow key={claim.claimId}>
            <TableCell>{claim.dateOfEvent}</TableCell>
            <TableCell>{titleCase(claim.claimType)}</TableCell>
            <TableCell>
              <Badge>{titleCase(claim.status)}</Badge>
            </TableCell>
            <TableCell>
              {claim.approvedAmount ? <MoneyText {...claim.approvedAmount} /> : null}
            </TableCell>
            <TableCell>
              <Link href={`/claims/${claim.claimId}`} className="underline underline-offset-2">
                View
              </Link>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
