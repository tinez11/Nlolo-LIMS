import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Read from disk, NOT imported. `css: false` in the vitest config makes every CSS import
 * resolve to an empty string -- including `?raw` -- which would leave the token set below
 * empty and this file's first rule reporting every class in the console as unknown.
 *
 * Resolved from the project root rather than `import.meta.url`, which vitest does not serve
 * as a file: URL here.
 */
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');

/**
 * Design drift as a failing test.
 *
 * Every rule here was a real finding, and every one was invisible to the rest of the suite:
 * a class naming a token that does not exist compiles, renders nothing, and passes every
 * behavioural test -- nine of them shipped that way (`bg-muted`, `text-fg-muted`). The
 * rules read source, not rendered output, so they catch the drift at the line it is written.
 */
const SOURCES = import.meta.glob('/src/**/*.tsx', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

/**
 * Comments removed, so prose ABOUT a class (there is plenty) is not read as a use of it.
 *
 * A block comment is replaced by its own newlines rather than by nothing: this console's
 * comments run to twenty lines, and collapsing them shifts every line number after them, so
 * a finding would point at the wrong line -- which is most of what a guard finding is for.
 */
function code(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, (block) => '\n'.repeat((block.match(/\n/g) ?? []).length))
    .replace(/(^|[^:])\/\/.*$/gm, '$1');
}

const files = Object.entries(SOURCES).filter(([path]) => !path.endsWith('.test.tsx'));

/** Every colour token the theme defines, read from index.css so the two cannot disagree. */
const TOKENS = new Set(
  [...css.matchAll(/--color-([a-z0-9-]+):/g)].map((m) => m[1]),
)
  .add('transparent')
  .add('current')
  .add('inherit')
  .add('white')
  .add('black');

/** Utilities that share a colour prefix but are not colours. */
const NOT_COLOURS: Record<string, Set<string>> = {
  text: new Set(['left', 'right', 'center', 'justify', 'start', 'end', 'xs', 'sm', 'base', 'lg',
    'xl', '2xl', '3xl', '4xl', 'wrap', 'nowrap', 'balance', 'pretty', 'ellipsis', 'clip']),
  bg: new Set(['clip', 'fixed', 'local', 'scroll', 'cover', 'contain', 'center', 'no-repeat',
    'repeat', 'none']),
  // `t`/`b`/`l`/`r` also carry widths (`border-t-0`), which the name pattern reads as a
  // colour called `t-0`; the side names below cover both forms.
  border: new Set(['t', 'b', 'l', 'r', 'x', 'y', 's', 'e', 't-0', 'b-0', 'l-0', 'r-0',
    'collapse', 'separate', 'dashed', 'dotted', 'solid', 'double', 'none', 'hidden']),
  ring: new Set(['inset', 'offset']),
};

function offenders(pattern: RegExp, except: (path: string) => boolean = () => false): string[] {
  const found: string[] = [];
  for (const [path, source] of files) {
    if (except(path)) continue;
    code(source).split('\n').forEach((line, index) => {
      if (pattern.test(line)) found.push(`${path}:${index + 1}`);
    });
  }
  return found;
}

describe('design guards', () => {
  it('names only colour tokens the theme defines', () => {
    const unknown: string[] = [];
    const utility = /(?<![\w-])(text|bg|border|ring)-([a-z][a-z0-9-]*)(?:\/\d+)?(?![\w-])/g;
    for (const [path, source] of files) {
      for (const [, prefix, name] of code(source).matchAll(utility)) {
        if (!prefix || !name) continue;
        if (NOT_COLOURS[prefix]?.has(name)) continue;
        if (!TOKENS.has(name)) unknown.push(`${path}: ${prefix}-${name}`);
      }
    }
    expect([...new Set(unknown)]).toEqual([]);
  });

  it('sets no text below 12px', () => {
    // Espresso's floor, and WCAG's practical one: 188 runs sat at 10-11px, carrying trace
    // ids, field notes, stat captions and the em dash that means "absent".
    expect(offenders(/text-\[(?:[0-9]|1[01])(?:\.\d+)?px\]/)).toEqual([]);
  });

  it('draws every checkbox with the Checkbox primitive', () => {
    expect(
      offenders(/type="checkbox"/, (path) => path.endsWith('/components/ui/checkbox.tsx')),
    ).toEqual([]);
  });

  it('never dims text with an alpha colour', () => {
    // `text-status-success-fg/80` at 11px was ~4.49:1 -- a pass on the page, a fail in the
    // arithmetic. Alpha on TEXT hides a contrast failure; a solid token cannot.
    expect(offenders(/(?<![\w-])text-[a-z-]+\/\d+/)).toEqual([]);
  });
});
