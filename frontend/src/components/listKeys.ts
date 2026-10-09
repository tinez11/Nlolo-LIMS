import { useEffect } from 'react';

/**
 * Keyboard paths through a list (2026-10-09, review B8): `j`/`k` move between rows, `Enter`
 * opens one (each row's activation is a real button, so that needs nothing here), `/` jumps to
 * the list's filter.
 *
 * For the people who work a queue all day -- an assessor down the claims list, an underwriter down
 * the cases -- for whom reaching for the mouse between every row is most of the work.
 *
 * Mounted once, in the shell. It reads the page rather than being told about it: rows are the
 * `[data-row-activate]` buttons `DataTable` renders, the filter is the `[data-list-filter]` input a
 * list screen marks, and both are looked for inside `#main` only. Silent while any field, select or
 * editable region has focus, and while a modifier is held, so it never eats a letter someone typed
 * or shadows a browser shortcut. Silent inside an open dialog, too: the list behind a drawer is not
 * the one being read.
 */
export function useListKeys(): void {
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) return;
      if (event.key !== 'j' && event.key !== 'k' && event.key !== '/') return;
      const target = event.target as HTMLElement | null;
      if (target && (isEditable(target) || target.closest('[role="dialog"]'))) return;

      const main = document.getElementById('main');
      if (!main) return;

      if (event.key === '/') {
        const filter = main.querySelector<HTMLInputElement>('[data-list-filter]');
        if (!filter) return;
        event.preventDefault();
        filter.focus();
        filter.select();
        return;
      }

      const rows = [...main.querySelectorAll<HTMLElement>('[data-row-activate]')];
      if (rows.length === 0) return;
      const current = rows.indexOf(document.activeElement as HTMLElement);
      const next =
        current === -1
          ? event.key === 'j'
            ? 0
            : rows.length - 1
          : Math.min(rows.length - 1, Math.max(0, current + (event.key === 'j' ? 1 : -1)));
      event.preventDefault();
      rows[next]?.focus();
      rows[next]?.scrollIntoView({ block: 'nearest' });
    }

    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);
}

function isEditable(element: HTMLElement): boolean {
  return (
    element instanceof HTMLInputElement ||
    element instanceof HTMLTextAreaElement ||
    element instanceof HTMLSelectElement ||
    element.isContentEditable
  );
}
