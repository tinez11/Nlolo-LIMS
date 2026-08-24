/**
 * Theme toggle. The key matches the inline script in index.html, which applies the
 * class before first paint so a dark-mode reload never flashes white.
 */

const KEY = 'lifeplatform.theme';
export type Theme = 'light' | 'dark';

function systemPrefersDark(): boolean {
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

export function currentTheme(): Theme {
  return document.documentElement.classList.contains('dark') ? 'dark' : 'light';
}

export function storedTheme(): Theme | null {
  try {
    const value = localStorage.getItem(KEY);
    return value === 'dark' || value === 'light' ? value : null;
  } catch {
    // Private modes throw on access, not just on write.
    return null;
  }
}

export function applyTheme(theme: Theme): void {
  document.documentElement.classList.toggle('dark', theme === 'dark');
  try {
    // Persist only a deliberate choice; absence means "follow the system".
    if (theme === (systemPrefersDark() ? 'dark' : 'light')) localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, theme);
  } catch {
    /* preference is not persistable here; the class is still applied */
  }
}

export function toggleTheme(): Theme {
  const next: Theme = currentTheme() === 'dark' ? 'light' : 'dark';
  applyTheme(next);
  return next;
}
