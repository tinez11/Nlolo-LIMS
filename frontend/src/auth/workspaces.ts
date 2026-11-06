import { SCREENS } from '@/screens';
import { workspacesOf, type Realm } from './realms';

/**
 * The workspaces this account holds that the console can open (2026-11-06). A workspace with no screens yet
 * (regulators) is not offered: a sign-in into an empty console is worse than saying so.
 */
export function availableWorkspaces(roles: readonly string[]): Realm[] {
  return workspacesOf(roles).filter((realm) => SCREENS[realm].length > 0);
}
