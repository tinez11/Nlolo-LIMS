import type { AccessChangeView } from '@/api/access';

const list = (value: unknown): string[] => (Array.isArray(value) ? value.map(String) : []);
const words = (codes: string[]) => codes.map((c) => c.replace(/_/g, ' ').toLowerCase()).join(', ');

/** One line for an entry in a person's access history, in words rather than codes. */
export function describeChange(c: AccessChangeView): string {
  const who = c.userName ?? 'a user';
  const d = c.detail as Record<string, unknown>;
  switch (c.changeType) {
    case 'IMPORTED':
      return `First signed in; roles carried over from the old login: ${words(list(d.roles)) || 'none'}`;
    case 'USER_CREATED':
      return `Added ${who} (${String(d.username ?? '')})`;
    case 'USER_DEACTIVATED':
      return `Deactivated ${who}`;
    case 'USER_ACTIVATED':
      return `Reactivated ${who}`;
    case 'PASSWORD_RESET':
      return `Gave ${who} a new temporary password`;
    case 'ROLE_CREATED':
      return `Created the role ${String(d.code ?? '')}`;
    case 'ROLE_CHANGED': {
      const added = list(d.added);
      const removed = list(d.removed);
      const parts = [added.length ? `added ${added.join(', ')}` : '', removed.length ? `removed ${removed.join(', ')}` : '']
        .filter(Boolean);
      return `Changed ${c.roleName ?? 'a role'}${parts.length ? `: ${parts.join('; ')}` : ''}`;
    }
    case 'ACCESS_CHANGED': {
      const parts = [
        list(d.addedRoles).length ? `gave ${words(list(d.addedRoles))}` : '',
        list(d.removedRoles).length ? `took away ${words(list(d.removedRoles))}` : '',
        list(d.addedPermissions).length ? `added ${list(d.addedPermissions).join(', ')}` : '',
        list(d.removedPermissions).length ? `removed ${list(d.removedPermissions).join(', ')}` : '',
      ].filter(Boolean);
      return `${who}: ${parts.length ? parts.join('; ') : 'access saved, nothing changed'}`;
    }
    default:
      return c.changeType;
  }
}
