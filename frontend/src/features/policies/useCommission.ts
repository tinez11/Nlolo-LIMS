import { useEffect, useState } from 'react';
import { getAgent, listAgents, listPolicyAccruals } from '@/api/distribution';
import { getPolicy } from '@/api/policies';
import type { AgentView, CommissionAccrualView, PolicyView } from '@/api/types';

export interface CommissionFacts {
  policy: PolicyView | null;
  agent: AgentView | null;
  lenderAgent: AgentView | null;
  accruals: CommissionAccrualView[];
}

/**
 * Who earns commission on a credit-life scheme, read once for the page: the rail states it and the work column sets it
 * up, and an act in one must show in the other at once.
 */
export function useCommission(policyNumber: string): { facts: CommissionFacts | null; refresh: () => void } {
  const [facts, setFacts] = useState<CommissionFacts | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const policy = await getPolicy(policyNumber).catch(() => null);
      const agentId = policy?.agentOfRecordId ?? null;
      const [agent, lenderAgents, accruals] = await Promise.all([
        agentId ? getAgent(agentId).catch(() => null) : Promise.resolve(null),
        policy?.policyholderPartyId
          ? listAgents({ partyId: policy.policyholderPartyId }).catch(() => null)
          : Promise.resolve(null),
        listPolicyAccruals(policyNumber).catch(() => [] as CommissionAccrualView[]),
      ]);
      if (!cancelled) setFacts({ policy, agent, lenderAgent: lenderAgents?.items[0] ?? null, accruals });
    })();
    return () => {
      cancelled = true;
    };
  }, [policyNumber, reload]);

  return { facts, refresh: () => setReload((n) => n + 1) };
}
