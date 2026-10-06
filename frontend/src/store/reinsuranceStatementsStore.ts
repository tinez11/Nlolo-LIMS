import { create } from 'zustand';
import {
  actOnStatement,
  attachStatementDocument,
  getStatement,
  listStatements,
  prepareStatement,
  rejectStatement,
  updateStatement,
  type StatementAction,
} from '@/api/reinsurance';
import type { ReinsuranceStatementStatus, ReinsuranceStatementView, UpdateReinsuranceStatementRequest } from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * The quarterly reinsurance statement (IFRS 17 I3d): the finance list, each treaty's statements, and the statement
 * open on screen. Every action runs under its own `acting` slot and refreshes the statement it changed, so a refusal
 * (a 422 listing every problem, a 409 with the reason) shows where it happened.
 */
interface ReinsuranceStatementsState {
  list: Resource<ReinsuranceStatementView[]>;
  byTreaty: Record<string, Resource<ReinsuranceStatementView[]>>;
  current: Resource<ReinsuranceStatementView>;
  acting: Record<string, Resource<unknown>>;

  loadList: (status?: ReinsuranceStatementStatus) => Promise<void>;
  loadForTreaty: (treatyId: string) => Promise<void>;
  load: (id: string) => Promise<void>;
  /** Resolves the new DRAFT, or null on a refusal. */
  prepare: (treatyId: string, quarter: string) => Promise<ReinsuranceStatementView | null>;
  update: (id: string, body: UpdateReinsuranceStatementRequest) => Promise<boolean>;
  attach: (id: string, file: File) => Promise<boolean>;
  act: (id: string, action: StatementAction) => Promise<boolean>;
  reject: (id: string, reason: string) => Promise<boolean>;
}

export const useReinsuranceStatementsStore = create<ReinsuranceStatementsState>((set, getState) => {
  /** Runs one call under acting[key]; resolves its result, or null when it was refused (track() never rethrows). */
  const run = async <T>(key: string, call: () => Promise<T>): Promise<T | null> => {
    let result: T | null = null;
    await track(
      `reinsuranceStatements.${key}`,
      getState().acting[key] ?? idle<unknown>(),
      (next) => set((s) => ({ acting: { ...s.acting, [key]: next } })),
      async () => {
        result = await call();
        return result;
      },
    );
    return getState().acting[key]?.status === 'success' ? result : null;
  };
  const refresh = (statement: ReinsuranceStatementView) =>
    set({ current: { status: 'success', data: statement, error: null, loadedAt: Date.now() } });

  return {
    list: idle(),
    byTreaty: {},
    current: idle(),
    acting: {},

    loadList: (status) =>
      track('reinsuranceStatements.list', getState().list, (next) => set({ list: next }), () => listStatements(status)),

    loadForTreaty: (treatyId) =>
      track(
        `reinsuranceStatements.treaty.${treatyId}`,
        getState().byTreaty[treatyId] ?? idle<ReinsuranceStatementView[]>(),
        (next) => set((s) => ({ byTreaty: { ...s.byTreaty, [treatyId]: next } })),
        () => listStatements(undefined, treatyId),
      ),

    load: (id) =>
      track('reinsuranceStatements.current', getState().current, (next) => set({ current: next }), () => getStatement(id)),

    prepare: async (treatyId, quarter) => {
      const draft = await run(`prepare.${treatyId}`, () => prepareStatement(treatyId, quarter));
      if (draft) refresh(draft);
      return draft;
    },

    update: async (id, body) => {
      const done = await run(`save.${id}`, () => updateStatement(id, body));
      if (done) refresh(done);
      return done !== null;
    },

    attach: async (id, file) => {
      const done = await run(`documents.${id}`, () => attachStatementDocument(id, file));
      if (done) refresh(done);
      return done !== null;
    },

    act: async (id, action) => {
      const done = await run(`statement.${id}`, () => actOnStatement(id, action));
      if (done) refresh(done);
      return done !== null;
    },

    reject: async (id, reason) => {
      const done = await run(`statement.${id}`, () => rejectStatement(id, reason));
      if (done) refresh(done);
      return done !== null;
    },
  };
});
