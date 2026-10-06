import { useEffect, useState } from 'react';
import { getPolicyClassifications } from '@/api/finaccounting';
import type { PolicyClassificationView, PolicyView } from '@/api/types';
import { Field } from '@/components/Field';
import { bucketLabel, channelLabel, modelLabel, portfolioLabel } from '@/lib/ifrs17';

const BASIS_NOTE: Record<string, string> = {
  REGISTER: "The accounting policy register's model for the portfolio",
  OVERRIDE: "The product version's override, which the register allows",
  OVERRIDE_REFUSED: 'The version asked for a model the register does not allow; the register’s applies',
};

/**
 * A policy's IFRS 17 classification (I2): the facts it was sold with, which every staff member may read, and -- for
 * finance and admin -- what finaccounting decided from them: the measurement model, why, and the group of contracts.
 * A vesting pension shows its second classification as a new contract.
 */
export function Ifrs17Classification({ policy, showAccounting }: { policy: PolicyView; showAccounting: boolean }) {
  const [rows, setRows] = useState<PolicyClassificationView[] | null>(null);
  const policyNumber = policy.policyNumber;

  useEffect(() => {
    if (!showAccounting || !policyNumber) return;
    let live = true;
    getPolicyClassifications(policyNumber)
      .then((r) => live && setRows(r))
      .catch(() => live && setRows([]));
    return () => {
      live = false;
    };
  }, [policyNumber, showAccounting]);

  return (
    <dl className="px-4 pb-2">
      <Field label="Portfolio" value={portfolioLabel(policy.portfolioCode)} />
      <Field label="Cohort" value={policy.cohortYear ?? '—'} />
      <Field label="Expected profitability" value={bucketLabel(policy.profitabilityBucket)} />
      <Field
        label="Model override"
        value={policy.measurementModelOverride ? modelLabel(policy.measurementModelOverride) : 'None'}
      />
      <Field label="Sales channel" value={channelLabel(policy.salesChannel)} />
      <Field label="Branch" value={policy.branchCode ?? '—'} />
      {showAccounting &&
        rows?.map((c) => (
          <div key={c.reason} className="mt-2 border-t border-border pt-2">
            <Field
              label={c.reason === 'VESTING' ? 'Group from vesting' : 'Group of contracts'}
              value={<span className="font-mono">{c.groupKey}</span>}
            />
            <Field
              label="Measurement model"
              value={modelLabel(c.measurementModel)}
              note={BASIS_NOTE[c.modelBasis] ?? c.modelBasis}
            />
            <Field label="Register version" value={c.registerVersion} />
          </div>
        ))}
      {showAccounting && rows?.length === 0 && (
        <Field label="Group of contracts" value="Not classified yet" />
      )}
    </dl>
  );
}
