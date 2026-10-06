import { create } from 'zustand';
import {
  actOnManualJournal,
  attachJournalDocument,
  createManualJournal,
  detachJournalDocument,
  getManualJournal,
  listJournalTemplates,
  listManualJournals,
  rejectManualJournal,
  reverseManualJournal,
  saveJournalTemplate,
  updateManualJournal,
  uploadJournalLines,
  type ManualJournalAction,
} from '@/api/finaccounting';
import type { JournalTemplateView, ManualJournalInput, ManualJournalLineInput, ManualJournalView } from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * Manual journals (IFRS 17 I4). The list, the journal open on screen, and the template library; every action runs
 * under its own `acting` slot and refreshes the journal it changed, so a refusal (a 422 listing every problem, or a
 * 409 with the reason) is shown where it happened.
 */
interface ManualJournalsState {
  list: Resource<ManualJournalView[]>;
  current: Resource<ManualJournalView>;
  templates: Resource<JournalTemplateView[]>;
  acting: Record<string, Resource<unknown>>;

  loadList: (status?: string) => Promise<void>;
  load: (id: string) => Promise<void>;
  loadTemplates: () => Promise<void>;
  /** Resolves the journal (created or changed) on success, null on a refusal. */
  save: (id: string | null, input: ManualJournalInput) => Promise<ManualJournalView | null>;
  act: (id: string, action: ManualJournalAction) => Promise<boolean>;
  reject: (id: string, reason: string) => Promise<boolean>;
  reverse: (id: string) => Promise<ManualJournalView | null>;
  attach: (id: string, file: File) => Promise<boolean>;
  detach: (id: string, documentRef: string) => Promise<boolean>;
  uploadLines: (id: string, file: File) => Promise<boolean>;
  saveTemplate: (name: string, lines: ManualJournalLineInput[], reasonCode: string | null) => Promise<boolean>;
}

export const useManualJournalsStore = create<ManualJournalsState>((set, getState) => {
  /** Runs one call under acting[key]; resolves its result, or null when it was refused (track() never rethrows). */
  const run = async <T>(key: string, call: () => Promise<T>): Promise<T | null> => {
    let result: T | null = null;
    await track(
      `manualJournals.${key}`,
      getState().acting[key] ?? idle<unknown>(),
      (next) => set((s) => ({ acting: { ...s.acting, [key]: next } })),
      async () => {
        result = await call();
        return result;
      },
    );
    return getState().acting[key]?.status === 'success' ? result : null;
  };
  const refresh = (journal: ManualJournalView) =>
    set({ current: { status: 'success', data: journal, error: null, loadedAt: Date.now() } });

  return {
    list: idle(),
    current: idle(),
    templates: idle(),
    acting: {},

    loadList: (status) =>
      track('manualJournals.list', getState().list, (next) => set({ list: next }), () => listManualJournals(status)),

    load: (id) => track('manualJournals.current', getState().current, (next) => set({ current: next }), () => getManualJournal(id)),

    loadTemplates: () =>
      track('manualJournals.templates', getState().templates, (next) => set({ templates: next }), () => listJournalTemplates()),

    save: async (id, input) => {
      const saved = await run('save', () => (id ? updateManualJournal(id, input) : createManualJournal(input)));
      if (saved) refresh(saved);
      return saved;
    },

    act: async (id, action) => {
      const done = await run(`journal.${id}`, () => actOnManualJournal(id, action));
      if (done) refresh(done);
      return done !== null;
    },

    reject: async (id, reason) => {
      const done = await run(`journal.${id}`, () => rejectManualJournal(id, reason));
      if (done) refresh(done);
      return done !== null;
    },

    reverse: (id) => run(`journal.${id}`, () => reverseManualJournal(id)),

    attach: async (id, file) => {
      const done = await run(`documents.${id}`, () => attachJournalDocument(id, file));
      if (done) refresh(done);
      return done !== null;
    },

    detach: async (id, documentRef) => {
      const done = await run(`documents.${id}`, () => detachJournalDocument(id, documentRef));
      if (done) refresh(done);
      return done !== null;
    },

    uploadLines: async (id, file) => {
      const done = await run(`lines.${id}`, () => uploadJournalLines(id, file));
      if (done) refresh(done);
      return done !== null;
    },

    saveTemplate: async (name, lines, reasonCode) => {
      const done = await run('template', () => saveJournalTemplate(name, null, lines, reasonCode));
      if (done) await getState().loadTemplates();
      return done !== null;
    },
  };
});
