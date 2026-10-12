import { useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Select } from '@/components/ui/input';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectOwnAgent, selectOwnTeam, useDistributionStore } from '@/store/distributionStore';
import { AgentCommission } from './AgentCommission';

/**
 * "My commission": what the signed-in agent -- or, for a supervisor, one agent in their downline --
 * has earned. The picker offers only `GET /agents/me/team`, which the server scopes from the token;
 * choosing an agent changes nothing about access, because each read is checked again server-side.
 * The choice lives in `?agent=` so a reload keeps it. An id outside the team falls back to self.
 */
export function MyCommissionPage() {
  const own = useDistributionStore(selectOwnAgent);
  const team = useDistributionStore(selectOwnTeam);
  const loadOwnAgent = useDistributionStore((s) => s.loadOwnAgent);
  const loadOwnTeam = useDistributionStore((s) => s.loadOwnTeam);
  const [params, setParams] = useSearchParams();

  useEffect(() => {
    void loadOwnAgent();
    void loadOwnTeam();
  }, [loadOwnAgent, loadOwnTeam]);

  if (isInitialLoad(own) || isInitialLoad(team)) return <LoadingBlock label="Loading your commission" />;

  if (own.data === null && own.status === 'error' && own.error) {
    return (
      <div className="px-6 pt-6">
        <ErrorPanel error={own.error} onRetry={() => void loadOwnAgent()} />
      </div>
    );
  }

  const me = own.data;
  if (!me) return null;

  // A team that failed to load leaves the agent with their own commission and no picker.
  const members = team.data ?? [];
  const viewed =
    members.find((m) => m.agentId === params.get('agent')) ?? members.find((m) => m.agentId === me.agentId);
  const viewedId = viewed?.agentId ?? me.agentId;
  const description = viewed
    ? `${viewed.displayName ?? 'Unnamed agent'} · ${viewed.licenseNumber}`
    : me.licenseNumber;

  const picker = members.length > 1 && (
    <label className="flex items-center gap-2 text-xs font-medium text-muted-foreground">
      Agent
      <Select
        inputSize="sm"
        value={viewedId}
        onChange={(e) => setParams(e.target.value === me.agentId ? {} : { agent: e.target.value })}
      >
        {members.map((m) => (
          <option key={m.agentId} value={m.agentId}>
            {m.agentId === me.agentId ? 'Me' : `${m.displayName ?? 'Unnamed agent'} (${m.licenseNumber})`}
          </option>
        ))}
      </Select>
    </label>
  );

  return (
    <>
      <PageHeader title="My commission" description={description} actions={picker || undefined} />
      <div className="space-y-5 px-6 pt-5 pb-8">
        <AgentCommission key={viewedId} agentId={viewedId} realm="agents" canManage={false} />
      </div>
    </>
  );
}
