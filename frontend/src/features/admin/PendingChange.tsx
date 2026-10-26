import type { AccessChangeView } from '@/api/access';
import { can } from '@/auth/access';
import { InlineError } from '@/components/InlineError';
import { Panel } from '@/components/Panel';
import { Button } from '@/components/ui/button';
import { formatInstant } from '@/lib/dates';
import { selectActing, useIamStore } from '@/store/iamStore';
import { describeChange } from './changeText';

/**
 * A change waiting for a second administrator (2026-10-26) -- decided on the record it changes, a person or a role,
 * by someone who neither asked for it nor is its subject; the server refuses either.
 */
export function PendingChange({ change, subjectIsMe }: { change: AccessChangeView; subjectIsMe: boolean }) {
  const acting = useIamStore(selectActing(change.changeId));
  const decide = useIamStore((s) => s.decide);
  const loadUser = useIamStore((s) => s.loadUser);
  const loadRoles = useIamStore((s) => s.loadRoles);
  const mayDecide = can('users.manage') && !subjectIsMe;

  async function act(approve: boolean) {
    await decide(change.changeId, approve);
    if (change.userId) void loadUser(change.userId);
    else void loadRoles();
  }

  return (
    <Panel emphasis title="Waiting for a second administrator" subtitle={describeChange(change)}>
      <div className="space-y-2 px-4 pb-4">
        <p className="text-xs text-muted-foreground">
          Asked by {change.requestedByName ?? 'an administrator'} on {formatInstant(change.requestedAt)}. It gives
          Administration, so it needs a second administrator who did not ask for it.
        </p>
        {acting.status === 'error' && acting.error && <InlineError error={acting.error} />}
        {mayDecide && (
          <div className="flex gap-2">
            <Button size="sm" variant="primary" pending={acting.status === 'loading'} onClick={() => void act(true)}>Approve</Button>
            <Button size="sm" variant="ghost" disabled={acting.status === 'loading'} onClick={() => void act(false)}>Reject</Button>
          </div>
        )}
      </div>
    </Panel>
  );
}
