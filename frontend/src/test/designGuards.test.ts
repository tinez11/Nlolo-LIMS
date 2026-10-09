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

/** The type tiers index.css defines (`--text-headline: …`), read the same way as the colours. */
const TYPE_TIERS = [...css.matchAll(/--text-([a-z]+):/g)].map((m) => m[1] as string);

/** Utilities that share a colour prefix but are not colours. */
const NOT_COLOURS: Record<string, Set<string>> = {
  text: new Set(['left', 'right', 'center', 'justify', 'start', 'end', 'xs', 'sm', 'base', 'lg',
    'xl', '2xl', '3xl', '4xl', 'wrap', 'nowrap', 'balance', 'pretty', 'ellipsis', 'clip',
    ...TYPE_TIERS]),
  bg: new Set(['clip', 'fixed', 'local', 'scroll', 'cover', 'contain', 'center', 'no-repeat',
    'repeat', 'none']),
  border: new Set(['t', 'b', 'l', 'r', 'x', 'y', 's', 'e',
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

/**
 * The same, over a window of consecutive lines.
 *
 * `offenders` is line-based, which is right for a class name and useless for a JSX element
 * whose opening tag, its role and its body sit three lines apart -- which is exactly the shape
 * of the error block the rule below catches.
 */
function spans(
  size: number,
  matches: (window: string) => boolean,
  except: (path: string) => boolean = () => false,
): string[] {
  const found: string[] = [];
  for (const [path, source] of files) {
    if (except(path)) continue;
    const lines = code(source).split('\n');
    lines.forEach((_line, index) => {
      if (matches(lines.slice(index, index + size).join(' '))) found.push(`${path}:${index + 1}`);
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
        // A side plus a width -- `border-b-2`, `border-t-0` -- is a border width, which the
        // name pattern would otherwise read as a colour called "b-2".
        if (prefix === 'border' && /^[tblrxyse]-\d+$/.test(name)) continue;
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

  /*
   * THE FOUR RULES BELOW EXIST BECAUSE THE SWEEP FAILED, REPEATEDLY.
   *
   * Each of these defects was removed lane by lane across a five-plan redesign, by grepping a
   * list of directories assembled by hand. That worked three times and failed three times: plan
   * 2 left fifteen error blocks and six renaming buttons in its OWN lane; plan 4 shipped a
   * commit saying "that clears the finance lane" while one of its screens had both, because the
   * directory list included another lane's folder and omitted one of its own; and the Panel rule
   * is newer still -- two of its five offenders sat in a lane already reviewed and merged.
   *
   * Trying harder is not a fix for that shape of mistake. A failing test is.
   */

  it('never renames a button while its request is in flight', () => {
    // `pending` exists for this. A control whose accessible NAME changes mid-request is a
    // different control to a screen reader and to every locator that looks for it, and a bare
    // `disabled` reports "unavailable" where `aria-busy` reports "working".
    //
    // Three of the eighteen also hid a SECOND condition in the same expression -- a failed
    // check, a separation-of-duties refusal, an empty form -- so the button claimed to be
    // working when it was refusing for a reason no waiting would resolve.
    //
    // A KNOWN AND DELIBERATE LIMIT: this is line-based, so a ternary broken across lines slips
    // through. That is the right trade rather than a gap to close. All eighteen were written on
    // one line, and the multi-line form is what a dropzone's live region legitimately uses --
    // "Uploading…" / "Drop the file" / "Drag a file here" is status text, not a label, and no
    // regex can tell it from a button's without reading structure. A rule that failed on that
    // would be switched off, and then it would catch nothing at all.
    expect(offenders(/status === 'loading'\s*\?\s*'/)).toEqual([]);
  });

  it('renders every request failure with InlineError', () => {
    // An ApiError carries a trace id, and a hand-rolled <p role="alert"> throws it away.
    //
    // Matched on the ApiError SHAPE, not on role="alert" alone, because eight other alerts on
    // this console are correct: form validation, strings from useState, and one server-stored
    // issuance reason that is a fact about the record rather than a failed call. A rule that
    // failed the build on those would be deleted rather than obeyed.
    expect(
      spans(
        5,
        (window) => /role="alert"/.test(window) && /\.detail \?\?/.test(window),
        (path) =>
          path.endsWith('/components/InlineError.tsx') || path.endsWith('/components/states.tsx'),
      ),
    ).toEqual([]);
  });

  it('offers the way out as a breadcrumb, never a back link', () => {
    // Twelve screens rendered a ghost button with a back arrow in a strip ABOVE the page bar --
    // navigation doing a breadcrumb's job while pushing the sticky bar 44px down every record.
    // A breadcrumb says where the record LIVES as well as offering the way out, and it rides
    // the bar instead of scrolling away with the first panel.
    //
    // Matched as the JSX ELEMENT, not the word: `DatePicker` handles `case 'ArrowLeft':` as a
    // keyboard key, and a rule that failed the build on correct keyboard handling would be
    // switched off within a week.
    expect(offenders(/<ArrowLeft[\s/>]/, (path) => !path.startsWith('/src/features/'))).toEqual([]);
  });

  it('draws every panel with the Panel primitive', () => {
    // Five sections reimplemented Panel's frame by hand -- same rounded border, same ruled
    // header, same `text-sm font-semibold` h2 -- so they looked right and inherited nothing:
    // not the scroll margin that makes a jumped-to section land below the sticky bars, not
    // `emphasis`, and not any later change to what a panel is.
    expect(offenders(/<section className="rounded-lg border border-border bg-surface"/)).toEqual([]);
  });

  it('clips the vertical axis of every sticky sideways scroller', () => {
    // `overflow-x: auto` computes `overflow-y` to `auto` as well. A sticky tab strip whose
    // triggers sit `-mb-px` on its rule overflows itself by a pixel, and that pixel grew a
    // vertical scrollbar -- the up/down arrows beside every policy's and client's tabs.
    expect(
      offenders(/^(?=.*\bsticky\b)(?=.*\boverflow-x-auto\b)(?!.*\boverflow-y-hidden\b)/),
    ).toEqual([]);
  });

  it('sets letter-spacing only through a type tier', () => {
    // Group captions had four spellings (tracking-wide x11, -wider, -[0.03em]) and the page title
    // took tracking-tight where the scale says -0.015em. The tiers in index.css carry tracking.
    // The one exception is a temporary password, spaced out so it can be read aloud letter by
    // letter -- reading, not typesetting.
    expect(
      offenders(
        /\btracking-(tight|tighter|wide|wider|widest|normal|\[)/,
        (path) => path.endsWith('/features/party/PortalAccessPanel.tsx'),
      ),
    ).toEqual([]);
  });

  it('sets no font size off the scale', () => {
    // `text-[13px]` on the small button and the filter chip: a size the scale does not have.
    // And the large sizes only through a tier: `text-lg` (1.125rem) is in no tier at all, and
    // `text-xl`/`text-2xl` were the headline and display tiers spelt by hand.
    expect(offenders(/\btext-(\[\d+(\.\d+)?(px|rem)\]|lg|xl|2xl|3xl|4xl)\b/)).toEqual([]);
  });

  it('writes an uppercase caption with the eyebrow tier', () => {
    // An uppercase line of small text IS a group caption (Uppercase-Is-Structure), and the
    // caption has one spelling. Uppercase on an input -- a currency code -- is not text-xs.
    expect(offenders(/^(?=.*\buppercase\b)(?=.*\btext-xs\b)/)).toEqual([]);
  });

  it('never sets money in monospace', () => {
    // Mono is for machine identifiers. Figures already compare down a column through the
    // global tabular-nums, and a mono TZS amount beside a sans one read as two kinds of number.
    expect(offenders(/font-mono[^\n]*formatMoney\(/)).toEqual([]);
  });

  it('writes a dash in visible copy as a dash, never as two hyphens', () => {
    // ` -- ` is how this codebase's comments spell an em dash, and comments are stripped
    // before this runs. Anything left is a string or JSX text, where the browser shows the
    // two hyphens as they are: "then publish a version -- a product with no version".
    expect(offenders(/\s--\s/)).toEqual([]);
  });
});
