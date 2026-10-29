import { create } from 'zustand';

/**
 * The customer portal's language (2026-10-29, the user: English and Swahili in this work). Remembered per browser -- a
 * convenience, so a blocked or private store simply starts in English. The staff and agent consoles stay English.
 */
export type Language = 'en' | 'sw';

const KEY = 'customer-language';

function stored(): Language {
  try {
    return localStorage.getItem(KEY) === 'sw' ? 'sw' : 'en';
  } catch {
    return 'en';
  }
}

interface LanguageState {
  language: Language;
  setLanguage: (language: Language) => void;
}

export const useLanguageStore = create<LanguageState>((set) => ({
  language: stored(),
  setLanguage: (language) => {
    try {
      localStorage.setItem(KEY, language);
    } catch {
      // Not remembered; the choice still holds for this visit.
    }
    set({ language });
  },
}));

export function useLanguage(): Language {
  return useLanguageStore((s) => s.language);
}
