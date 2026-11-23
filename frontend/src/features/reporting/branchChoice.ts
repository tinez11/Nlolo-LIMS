import { useEffect, useMemo } from 'react';
import { useAccessStore } from '@/store/accessStore';
import { useOrganisationStore } from '@/store/organisationStore';

/**
 * The branch choice on the dashboards and reports (2026-11-23, branch views:
 * backend/docs/superpowers/specs/2026-11-23-branch-views-design.md). Views, not walls: anyone may choose any branch.
 *
 * The choice lives in the page address as `branch` -- a branch code, or `all` -- beside the period, so back, forward,
 * a bookmark and a shared link all show exactly what was seen. With no `branch` in the address, a person starts on their
 * home branch when it is not the head office, and on every branch otherwise.
 */

/** Every branch: the address's `branch=all`, and the select's first option. */
export const ALL_BRANCHES = 'all';

export interface RegisterBranch {
  code: string;
  name: string;
  active: boolean;
  headOffice: boolean;
}

export interface BranchOption {
  code: string;
  name: string;
  /** What the select shows: the name, and "(your branch)" after a non-head-office person's own. */
  label: string;
}

/** `ALL_BRANCHES` or a branch code: the address's choice, else the starting rule. */
export function chosenBranch(param: string | null, homeBranch: string | null, headOffice: string | null): string {
  if (param) return param;
  return homeBranch && homeBranch !== headOffice ? homeBranch : ALL_BRANCHES;
}

/** The register's open branches, head office first, then in the register's order; your own marked unless head office. */
export function branchOptions(branches: readonly RegisterBranch[], homeBranch: string | null): BranchOption[] {
  const open = branches.filter((b) => b.active);
  const ordered = [...open.filter((b) => b.headOffice), ...open.filter((b) => !b.headOffice)];
  return ordered.map((b) => ({
    code: b.code,
    name: b.name,
    label: b.code === homeBranch && !b.headOffice ? `${b.name} (your branch)` : b.name,
  }));
}

/** "Arusha branch" for a chosen branch -- the code while the register loads -- and null for every branch. */
export function branchWords(chosen: string, options: readonly BranchOption[]): string | null {
  if (chosen === ALL_BRANCHES) return null;
  const name = options.find((o) => o.code === chosen)?.name ?? chosen;
  return `${name} branch`;
}

/**
 * The register and where the signed-in person is based, for a screen that offers the choice: the dashboard and the
 * reports read them the same way. The register is loaded once and shared (organisationStore).
 */
export function useBranchRegister(): {
  homeBranch: string | null;
  headOffice: string | null;
  options: BranchOption[];
  /**
   * Whether the starting rule can be applied: nobody based anywhere needs the register, and for a person based at a
   * branch the register must have answered -- until it has, nothing says whether their branch is the head office.
   */
  ready: boolean;
} {
  const homeBranch = useAccessStore((s) => s.mine.data?.homeBranch ?? null);
  const branches = useOrganisationStore((s) => s.branches);
  const loadBranches = useOrganisationStore((s) => s.loadBranches);
  useEffect(() => {
    void loadBranches();
  }, [loadBranches]);
  const register = branches.data;
  const options = useMemo(() => branchOptions(register ?? [], homeBranch), [register, homeBranch]);
  const headOffice = register?.find((b) => b.headOffice)?.code ?? null;
  const ready = !homeBranch || register !== null || branches.status === 'error';
  return { homeBranch, headOffice, options, ready };
}
