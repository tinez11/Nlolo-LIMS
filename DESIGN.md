---
name: Life Platform Console
description: A ledger-sober, vendor-neutral operating console for life insurance administration — ink on paper, hairline rules, and colour reserved for state.
colors:
  ink: "oklch(0.16 0 0)"
  ink-inverse: "oklch(0.99 0 0)"
  paper: "oklch(1 0 0)"
  paper-muted: "oklch(0.985 0 0)"
  muted-ink: "oklch(0.52 0 0)"
  subtle-ink: "oklch(0.55 0 0)"
  rule: "oklch(0.918 0 0)"
  rule-strong: "oklch(0.85 0 0)"
  input: "oklch(0.66 0 0)"
  control: "oklch(0.965 0 0)"
  control-hover: "oklch(0.935 0 0)"
  band: "oklch(0.965 0 0)"
  hover: "oklch(0.968 0 0)"
  selected: "oklch(0.955 0 0)"
  status-neutral-bg: "oklch(0.96 0.002 250)"
  status-neutral-fg: "oklch(0.42 0.008 250)"
  status-pending-bg: "oklch(0.95 0.032 240)"
  status-pending-fg: "oklch(0.45 0.14 250)"
  status-active-bg: "oklch(0.95 0.036 190)"
  status-active-fg: "oklch(0.44 0.09 195)"
  status-success-bg: "oklch(0.95 0.043 150)"
  status-success-fg: "oklch(0.43 0.11 152)"
  status-warning-bg: "oklch(0.96 0.055 92)"
  status-warning-fg: "oklch(0.47 0.11 75)"
  status-danger-bg: "oklch(0.95 0.035 22)"
  status-danger-fg: "oklch(0.48 0.17 25)"
typography:
  display:
    fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "-0.015em"
    fontVariant: "tabular-nums"
  headline:
    fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"
    fontSize: "1.25rem"
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: "-0.015em"
  title:
    fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"
    fontSize: "1rem"
    fontWeight: 600
    lineHeight: 1.4
  body:
    fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.45
  label:
    fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 500
    lineHeight: 1.4
  micro:
    fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 400
    lineHeight: 1.4
  eyebrow:
    fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 500
    lineHeight: 1.4
    letterSpacing: "0.03em"
    textTransform: "uppercase"
  mono:
    fontFamily: "ui-monospace, 'SF Mono', 'Cascadia Mono', Menlo, monospace"
    fontSize: "0.75rem"
    fontWeight: 400
    lineHeight: 1.4
rounded:
  xs: "0.25rem"
  sm: "0.375rem"
  md: "0.5rem"
  lg: "0.625rem"
  full: "9999px"
spacing:
  hairline-gap: "2px"
  tight: "6px"
  snug: "8px"
  cosy: "12px"
  cell: "16px"
  panel: "20px"
  gutter: "24px"
components:
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.ink-inverse}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "0 14px"
    height: "36px"
  button-secondary:
    backgroundColor: "{colors.control}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "0 14px"
    height: "36px"
    pointerCoarseHeight: "44px"
  button-outline:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    borderColor: "{colors.rule-strong}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "0 14px"
    height: "36px"
    pointerCoarseHeight: "44px"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "0 14px"
    height: "36px"
  button-ghost-hover:
    backgroundColor: "{colors.hover}"
  button-danger:
    backgroundColor: "{colors.status-danger-fg}"
    textColor: "{colors.ink-inverse}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "0 14px"
    height: "36px"
  button-sm:
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "0 10px"
    height: "32px"
  button-icon:
    rounded: "{rounded.md}"
    height: "32px"
    width: "32px"
  input-text:
    backgroundColor: "{colors.control}"
    borderColor: "{colors.input}"
    focusBackgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "0 10px"
    height: "36px"
    pointerCoarseHeight: "44px"
  status-badge:
    typography: "{typography.label}"
    rounded: "{rounded.full}"
    padding: "2px 8px"
  card-stat:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    rounded: "{rounded.lg}"
    padding: "12px 16px"
  panel:
    backgroundColor: "{colors.paper}"
    rounded: "{rounded.lg}"
    padding: "16px"
  table-header-cell:
    typography: "{typography.label}"
    textColor: "{colors.muted-ink}"
    padding: "10px 16px"
  table-row:
    backgroundColor: "{colors.paper}"
    typography: "{typography.body}"
    height: "44px"
  table-row-hover:
    backgroundColor: "{colors.hover}"
  table-row-selected:
    backgroundColor: "{colors.selected}"
  nav-item:
    backgroundColor: "transparent"
    textColor: "{colors.muted-ink}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "8px 10px"
  nav-item-active:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    fontWeight: 500
    shadow: "raise"
  sidebar:
    backgroundColor: "{colors.paper-muted}"
    width: "240px"
  slide-over:
    backgroundColor: "{colors.paper}"
    width: "28rem"
    padding: "16px 20px"
---

# Design System: Life Platform Console

## Overview

**Creative North Star: "The Ledger"**

This console is a bound accounting ledger rendered in software. Ink on paper,
ruled hairlines, figures standing in true columns, and colour used only where a
clerk would once have reached for a coloured stamp. Nothing here is decorative,
because nothing in a ledger ever was: the beauty is in the ruling, the alignment
and the fact that the totals can be trusted. A staff member spends their whole
working day inside this surface, so it is built to be read rather than admired,
and to be read for eight hours without fatigue.

The doctrine that gives the world its edge is inherited straight from the
product: **render only what the platform can prove.** There are no analytics
endpoints on this platform, so there are no trend arrows, no sparklines and no
percentage deltas. The stat row shows counts. A status the build has never seen
is ringed and flagged rather than quietly dressed as a neutral. An absent value
is an em dash, never a grey pill reading "Unknown". This refusal is not a
limitation the design works around — it is the design's argument, and it is what
makes the console feel like a record instead of a dashboard.

Character is **confident and tactile**. The palette is achromatic and the
structure is flat, but the controls should feel pressable and the boundaries
should feel drawn on purpose — a ledger is a physical object, and the pen has
weight. This is a deliberate small step forward from the incumbent build, which
is quieter than this: hover today is a background-colour swap and little else.
Where this file states a tactile behaviour that the code has not yet grown, it is
stating the target, and the gap is a backlog item rather than licence to invent a
different world. The system is emphatically not soft, rounded, playful,
gradient-lit or glassy, and it carries no insurer's brand — several insurers are
tenants of the same console, so the identity belongs to the platform.

**Key Characteristics:**

- Achromatic by default; six status hues are the entire chromatic vocabulary
- Space, tone and three kinds of hairline carry the structure — no shadows at rest
  (see *The Three Lines Rule*)
- Tabular figures globally, so every money and date column reads as a column
- A compressed, functional type scale that prizes density over display
- Dark mode is a first-class peer, not a filter: every token is defined twice
- Vendor-neutral and tenant-invisible by construction

### Espresso, adapted

The console's **structure** is taken from Frappe's Espresso (the ERPNext desk),
measured from its own published `desk.bundle` stylesheet rather than eyeballed
from a screenshot: a sticky page bar carrying breadcrumb, title, status and
actions; sticky record tabs beneath it; a grey band on table headers; filled
controls; a raised pill for the active nav item; 8px control radius; and a
12px type floor. All of it suits a console whose users sit in it all day, and
none of it touches the palette — the ink, the paper and the six stamps are
unchanged, because the identity is not Espresso's to lend.

**Its contrast values are deliberately NOT adopted.** Four of them fail WCAG AA,
measured from those same published tokens:

| Espresso | Measured | Needs |
| --- | --- | --- |
| Borderless filled field (`#f3f3f3` on white) | 1.11:1 | 3:1 (1.4.11) |
| Focus ring `#c9c9c9` on white | 1.66:1 | 3:1 |
| `text-light` `#7c7c7c` — inactive tabs, meta | 4.17:1 | 4.5:1 |
| Checkbox border `#999` | 2.85:1 | 3:1 |

So this console takes the filled ground **and keeps its own 3:1 `input` border
on top of it**, keeps the ink focus ring, uses Muted Ink (~7:1) where Espresso
uses `text-light`, and gives the active nav item weight as well as a shadow —
Espresso's white-on-grey pill is 1.06:1 and leans on the shadow alone. Espresso
sizes controls at 28px; this console stays at 32/36px and lifts every control to
44px under `pointer-coarse`, which catches a touch laptop as well as a phone.

### Two shapes for a record, and the rule for choosing

A record with many sections gets one of exactly two structures, and the choice is
decided by **how the work is done, not by how much of it there is**.

**Tabs (`RecordTabs`)** — when the sections are separate jobs done by separate
people, and nobody needs two of them at once. A policy's beneficiaries, invoices,
loans, messages and cessions are five such jobs, so four stay unmounted and load
when opened. The tab lives in the URL, so a section can be linked to and Back
still works.

**A section bar (`SectionNav`)** — when the sections must stay on one scroll,
because a person reads one while writing another. A claim is the case: an assessor
reads the death certificate *while* writing the findings that cite it, so tabs
would put the evidence one click from the form quoting it. Nothing is hidden; the
bar only makes the fifth section a jump instead of a drag, and says which one you
are in.

Both stick under the page bar, so three things now compete for the top of the
viewport. Each publishes its own height — `--pagebar-h`, `--sectionbar-h` — and
everything that must clear them reads both: `Panel`'s scroll margin, so a jumped-to
panel lands below them rather than behind them, and `DetailLayout`'s pinned record
rail. A page with no section bar leaves `--sectionbar-h` unset, and the
`var(…, 0px)` fallback is what keeps every one of those pages unchanged.

## Colors

An achromatic ink-on-paper base carrying six low-chroma status hues, which are
the only colour permitted anywhere in the system.

### Primary

- **Ledger Ink** (`oklch(0.16 0 0)`): the near-black that is simultaneously the
  body text colour, the primary button ground, and the focus ring. It is the only
  "brand" colour the system has, and it is deliberately a neutral — a console
  shared by competing insurers cannot carry a hue that belongs to one of them. In
  dark mode ink and paper swap roles rather than dimming.
- **Paper White** (`oklch(1 0 0)`): the surface every panel, table, card and
  slide-over sits on. Pure white in light mode; `oklch(0.185 0 0)` in dark, one
  step *above* the page ground so a panel reads as laid on the page.

### Neutral

- **Page Ground** (`oklch(1 0 0)` light / `oklch(0.145 0 0)` dark): the body
  backdrop. In light mode it is identical to Paper White by design — separation
  comes from rules, not tone. In dark mode it is one step darker than a panel,
  which is where the tonal relationship actually does the work.
- **Quiet Paper** (`oklch(0.985 0 0)`): the sidebar ground and the skeleton-row
  fill. The one place a tonal shift, rather than a rule, marks a region.
- **Muted Ink** (`oklch(0.52 0 0)`): labels, table headers, descriptions,
  inactive nav items. The workhorse secondary — roughly 70% of all text on screen.
- **Subtle Ink** (`oklch(0.55 0 0)`): the quietest legible tier — the em dash that
  stands for an absent value, nav group captions, field notes, trace IDs. 4.85:1
  on Paper. It was `0.63` and therefore 3.50:1, which is a fail at the 11px this
  tier is always rendered at: there is no large-text exemption to appeal to, and
  a trace ID nobody can read is a support call nobody can close.
- **Rule** (`oklch(0.918 0 0)`): the hairline. Applied globally to `*` as the
  default border colour, so any element that grows a border grows the right one.
- **Rule Strong** (`oklch(0.85 0 0)`): a rule that is doing more work — a selected
  stat card, a hovered interactive card, the ring around an unrecognised status.
- **Input Rule** (`oklch(0.66 0 0)` light / `oklch(1 0 0 / 38%)` dark): the edge of
  a control, and **deliberately darker than Rule**. The two were the same value,
  which read as tidy and was wrong: a panel edge is decoration, while the edge of
  a field is the only thing saying *you can type here*, and WCAG 1.4.11 asks 3:1
  of it. At `0.918` it was 1.27:1. This is 3.11:1.
- **Hover** (`oklch(0.968 0 0)`) and **Selected** (`oklch(0.955 0 0)`): the two
  interaction grounds. Selected is deliberately one step deeper than hover so a
  hovered row and a selected row are never confusable.

### The six status hues

Not "accents" — a fixed semantic vocabulary onto which roughly 25 backend enums
are mapped. They are distinct hues rather than shades of one, because a finance
officer scanning a table reads hue before text, and mistaking `IN_DOUBT` for
`FAILED` is an operational error rather than a cosmetic one. Each is a
low-chroma tinted ground carrying a saturated same-hue foreground.

- **Slate** — *neutral* (`oklch(0.96 0.002 250)` / `oklch(0.42 0.008 250)`):
  closed without incident, or no state worth reporting. `WAIVED` lives here.
- **Periwinkle** — *pending* (`oklch(0.95 0.032 240)` / `oklch(0.45 0.14 250)`):
  waiting on someone. `REOPENED` lives here.
- **Teal** — *active* (`oklch(0.95 0.036 190)` / `oklch(0.44 0.09 195)`): live and
  running normally. In force, in progress.
- **Moss** — *success* (`oklch(0.95 0.043 150)` / `oklch(0.43 0.11 152)`): a good
  terminal outcome. `LOADED` lives here — an acceptance, with loading.
- **Amber** — *warning* (`oklch(0.96 0.055 92)` / `oklch(0.47 0.11 75)`):
  unresolved, needs a person. `IN_DOUBT`, `POSTPONED` and `IN_GRACE` live here.
- **Rust** — *danger* (`oklch(0.95 0.035 22)` / `oklch(0.48 0.17 25)`): failed,
  refused or forced. `FORCED_LAPSE_TRIGGERED` lives here.

In dark mode the pair inverts — a dim tinted ground with a bright foreground — and
the hue order is preserved exactly, so the mapping is learnable once and holds in
both themes.

### Named Rules

**The Six Buckets Rule.** Six status buckets are the complete chromatic
vocabulary of this system. A seventh bucket is never added; a new backend enum is
mapped onto an existing one, and if it genuinely fits none, that is a product
conversation and not a colour decision.

**The Stamp Rule.** Colour reports state and does nothing else. It never marks
hierarchy, never brands a section, never highlights a call to action, and never
decorates. A screen on which nothing notable is happening is entirely
achromatic — and that emptiness is information.

**The Both Themes Rule.** Every colour is declared as a token in `:root` *and*
redefined in `.dark`. A component never branches on theme and never hard-codes a
hex. A colour that exists in only one theme is not finished.

**The Inverted-Pair Rule.** When a status hue is used as a solid ground (the gate
panel's check glyphs), its foreground is the *matching* `-bg` token, never white.
The pair inverts together across themes, so `text-white` on a `-fg` ground would
be white-on-light in dark mode.

## Typography

**Display Font:** Inter (none — Inter carries the whole system)
**Body Font:** Inter, with `ui-sans-serif`, `system-ui`, `-apple-system`,
`'Segoe UI'`, `Roboto` behind it
**Mono Font:** `ui-monospace`, `'SF Mono'`, `'Cascadia Mono'`, `Menlo`, reserved
almost entirely for trace IDs and machine identifiers

**Character:** One neutral grotesque doing every job, tuned for figures rather
than for voice. The personality is not in the letterforms — it is in the
`font-variant-numeric: tabular-nums` set globally on `body`, which is what makes
a TZS column read as a column instead of a ragged list. Inter is chosen for its
tall x-height and unambiguous `1`/`l`/`I` and `0`/`O`, which matters when the
figures are policy numbers and money.

### Hierarchy

**The scale is compressed on purpose, and it is compressed further than a
publication scale would admit.** This is a confirmed decision, not drift: a
back-office operator wants rows on screen, and density beats comfort here. The
measured distribution was 226 uses of 0.75rem, 98 of 0.875rem, 94 of 0.6875rem,
16 of 0.625rem, and exactly one each of the two largest sizes. Read that as the
real hierarchy: **0.75rem is the workhorse of this console**, and 0.875rem is
reserved for the values a person actually reads a row to find.

**0.75rem is now also the floor.** The 0.6875rem and 0.625rem tiers are gone:
168 hard-coded runs across 59 files, carrying trace IDs, field notes, stat
captions and the em dash that means "absent" — the tier a console reaches for
when a value is secondary, which is not the same as unreadable. Espresso's own
floor is 12px, and a guard test (`src/test/designGuards.test.ts`) keeps it.
Where a caption and its neighbour are now the same size, hierarchy is carried by
ink (Subtle against Muted) and by case, which it largely already was.

- **Display** (600, 1.5rem, tracking-tight): the stat-card figure, and nothing
  else. One occurrence in the codebase, by design — and now on **one screen**,
  the group-scheme member register, which is the only screen left with more than
  one number to compare. The other nine registers carried a single count in a
  four-column card grid; that count is a Micro line in the page header instead
  (`CountLine`), so a list screen no longer has a Display figure at all.
- **Headline** (600, 1.25rem, tracking-tight): the page `<h1>` in `PageHeader`.
  One occurrence, because one component owns it.
- **Title** (600, 1rem): the slide-over title, the emphasised panel heading, and
  the heaviest in-panel headings. It went unused on detail pages for a long time
  while every panel heading sat at Body — which is why an eight-panel client
  record read as eight equal boxes. `Panel emphasis` is what claims it.
- **Body** (400, 0.875rem): the *content* tier — table cell values, form control
  text, buttons, nav items, gate titles. Reserved for what carries the answer.
- **Label** (500, 0.75rem): the **most-used size in the system by a wide margin**,
  and the true default for chrome — table headers, field labels, descriptions,
  stat labels, form captions, most secondary cell content.
- **Micro** (400, 0.75rem): notes, error messages, badge counts, trace IDs,
  stat hints. The floor, and now genuinely the same size as Label — the two are
  separated by ink, not by size.
- **Eyebrow** (500, 0.75rem, +0.03em, uppercase): sidebar group captions and
  gate-panel titles. The only uppercase in the system, and it is structural
  labelling, never a marketing device.

Weights are three: 400, 500 and 600. A single 700 exists, on the 10px glyph
inside a gate marker. Nothing lighter than 400 appears anywhere.

**Two live exceptions, recorded rather than tidied away.** `ui/button.tsx`
introduces a 0.8125rem for its small size, and `RealmPicker` uses a 1.125rem
`<h1>`; neither size exists in the scale above, and both are drift rather than
decision. Separately, 16 uses of 0.625rem sit below the stated floor — density
justifies 0.75rem, but nothing justifies 10px, and those are the one part of the
distribution worth raising.

### Named Rules

**The Tabular Rule.** `font-variant-numeric: tabular-nums` is set on `body` and
is never overridden. Every figure in this product is meant to be compared
vertically against the figure above it.

**The Two-Peaks Rule.** Exactly two type sizes above body exist on any screen: the
page headline and one second peak — the stat figure on a screen that still has a
stat row, or the emphasised panel heading on a detail page. A screen that grows a
third display size has invented a hierarchy the system does not have.

The rule binds in both directions, and it is why `GroupSchemePage` leads with its
add-member panel but does **not** emphasise it: that screen keeps a real stat row,
so a 1rem heading beside a 1.5rem figure and a 1.25rem title would be three sizes
above body. Where a fix for one thing would break this, the fix loses.

**The Uppercase-Is-Structure Rule.** Uppercase marks a navigational or sectional
group caption. It is never used for a button, a badge, a status, or emphasis.

## Layout

**The shell.** A fixed 240px sidebar (224px until 2026-10-09, where "Corporate/Group" beside its count truncated) in Quiet Paper with a hairline right rule,
against a scrolling main column. The sidebar holds a wordmark block, the
role-gated nav groups, and a user block pinned to the bottom by a top rule. Nav
groups are ordered along the business flow — Clients, New business, Policies &
claims, Finance, Distribution, Records, Configuration — with configuration last
because authoring a product is rare set-up rather than daily work.

**The shell owns the viewport, and the page never scrolls.** The shell is
`fixed inset-0`, and `main` is the one vertical scroller. This is a rule, not an
implementation detail: with a percentage height (`h-full`) the shell measured
correctly at every viewport, yet 161px of layout overflow escaped `main` and gave
the document a second scrollbar underneath main's own. Scrolling a register to
the bottom then lifted the whole shell off the viewport floor, leaving the
sidebar short by that much with a band of bare background beneath it — visible at
1920x900, invisible at 1920x1080, so it hid from anyone testing on a tall window.
Clipping did not cure it; `overflow: hidden` on the shell, `#root`, `body` and
`html` all left the overflow intact. Nothing may reintroduce a second scroller:
if a pane needs to scroll, it scrolls inside `main`.

**The page rhythm.** A 24px page gutter governs everything: the header block
(24px sides, 24px top, 16px bottom), the stat row (24px sides, 20px bottom), and
the content beneath. Table cells step inward to 16px; slide-over content sits at
20px. These four values — 24 / 20 / 16 / 12 — are the spacing spine, and 8px and
6px handle intra-component gaps.

**Density.** One comfortable row density, fixed: table rows are 44px tall, which
is also the minimum comfortable pointer target, so row height and hit target are
the same decision. Controls come in two heights — 36px for a primary control,
32px for a compact or in-table one.

**Stat row.** A wrapping row of cards sized to their content — each one flexes
between 9rem and 15rem, 12px gaps — rather than a fixed grid. It was
`grid-cols-2 lg:grid-cols-4` regardless of how many stats it was handed, which is
how nine screens ended up with one 282px card and three empty columns.

**The detail page.** `DetailLayout` owns it: a `minmax(0,1fr)` work column and a
320px record rail, 20px gaps, collapsing to one column below `lg`.

- **The rail is the record, and it is pinned when it fits.** Identifying facts
  only — who, what, how much, as of when. `sticky` below the page bar, but only
  while the rail is shorter than the space available: `shouldPin` in
  `railPin.ts` compares the rail against the viewport less the bars and a 2rem
  margin, and a rail that does not fit scrolls with the page instead. Capping it
  at `100dvh - 3rem` with its own `overflow-y` was the earlier answer and was
  reverted: it put a third scrollbar on screen beside the sidebar's and the
  page's, and the credit-life scheme record had all three at once. Un-pinning
  keeps the last rows reachable without that scrollbar.
- **The work column is ordered by task.** The acting panel leads where the page
  exists to perform an act, then the registers that evidence it. Read-only tables
  belong here rather than in the rail, which is where they were: a recoveries
  table and a member's filename both need more than 320px.
- **Bounded panels before unbounded ones.** After the act, a panel of a few fixed
  rows — the terms of a contract — precedes a ledger that grows for the life of
  the record. A page that leads with unbounded height buries everything after it,
  and on the policy page it buried the beneficiary editor under twenty-plus
  invoices until the editor could not be reached at all.
- **Nothing that mutates goes in the rail.** See The Record-Rail Rule below.
- Below `lg` the record falls after the work, with no `order` utilities, so DOM
  order still matches visual order for keyboard and assistive tech at every width.

**Responsive posture.** Desktop-first and honest about it: the console is built
for a back-office desk and a large monitor. The only breakpoint doing real work
is `sm`, which reveals table columns marked `secondary` — columns that are useful
but not identifying, hidden on narrow viewports so the identifying column always
survives. Below `md` the sidebar collapses to a drawer: a hamburger in a top bar
opens it, a pointer dismiss layer and Escape close it, and it carries `invisible`
rather than a transform alone so a phone user does not tab through twenty-two
off-screen destinations before reaching the page. Touch targets step up to 44px
under `pointer-coarse`. Narrow-viewport work beyond this is still new design
rather than a rule to be looked up here.

**Overflow.** A wide table scrolls inside its own `overflow-x` container. The
page body never scrolls horizontally.

### Named Rules

**The 24 Rule.** The page gutter is 24px, and every element that meets the page
edge aligns to it — header, stat row, empty state, error panel. An element that
sets its own page margin has broken the ledger's ruling.

**The Record-Rail Rule.** The 320px rail holds facts and never an action. It is
the ledger's record header — the figures you must not lose sight of while working
the entries beside them — which is exactly why it is pinned, and exactly why a
settlement, a suspension or a KYC decision cannot live in it. *Preview is
dismissable; acting is not* applies to a margin as much as to a drawer: a person
reads a 320px column as a summary, and an action they read as a summary is an
action they take without reading. The policy page had five panels in that rail,
including its suspend/resume/reinstate control, and only two in the wide column.

**The Acting-Panel Rule.** One panel per detail page may take `emphasis`, and only
where the page exists to perform that act — an assessment, a settlement decision, a
KYC decision. It leads its column. A page whose act is occasional (a policy
lifecycle change, publishing a product version) still lifts the act out of the
rail, but takes no emphasis: promoting a rare destructive action above the record
somebody actually came to read is a different mistake, not a fix. Emphasis is
achromatic — Title tier and a Rule-Strong edge — because of the Stamp Rule.

**The 44px Row Rule.** A table row is 44px and the activation control fills it.
Row height and touch target are one number, so they cannot drift apart.

## Elevation & Depth

**This system is flat, and that is law.** Structure is carried by space, by the
three hairlines of *The Three Lines Rule*, by the table header's band, and, in
dark mode, by a two-step tonal relationship between the page ground and panel
surfaces. Thirty container instances across the codebase
use a hairline border and no shadow. Nothing lifts on hover, nothing has a
resting shadow, and there is no ambient depth of any kind.

Shadow exists in exactly three places in the entire product, and all three are
the same idea: a layer that genuinely floats above the page and needs to be read
as detached from it — the right-hand slide-over, and two popovers.

### Shadow Vocabulary

- **Floating layer** (`box-shadow: 0 20px 25px -5px rgb(0 0 0 / 0.1), 0 8px 10px -6px rgb(0 0 0 / 0.1)`):
  the slide-over. Paired with a `border-l` hairline and a `black/20` backdrop
  (`black/50` in dark), because the shadow alone does not separate reliably
  against a dark ground.
- **Floating menu** (`box-shadow: 0 10px 15px -3px rgb(0 0 0 / 0.1), 0 4px 6px -4px rgb(0 0 0 / 0.1)`):
  popovers — the date picker and the party picker.

### Named Rules

**The Floating-Layer Rule.** A shadow means "this element is not on the page." If
the element is on the page, it has a hairline and no shadow. There is no third
option, and there is no such thing as a subtle resting elevation here. (Two
`box-shadow`s are not shadows in this sense and are the only exceptions: the 1px
band-coloured seam cover on table header cells, and the inset 1px rule of the
record tabs. Both are drawn in the colour of the surface or the rule they stand
for, and neither lifts anything.)

**The Three Lines Rule.** Only three kinds of line remain: **a box's outline, the
faint rule between table rows, and the tab strip's rule.** Everything else is
separated by space or by tone. The page bar draws its rule only while content is
passing under it. A panel's title is not ruled off from its content, a table's
header band is not ruled off from its rows, and a `Field` list is not ruled
between fields. Adopted 2026-10-09 after a policy's Billing tab was counted at
about twenty horizontal lines against about six on a reference settings page. Not
covered: the edges of controls keep their 3:1 Input Rule border (WCAG 1.4.11).

## Shapes

A single 6px radius carries the system: 151 of the 207 radius applications in the
codebase are `rounded-md`. It is soft enough not to read as brutalist and tight
enough to keep a dense table from looking like a set of lozenges.

Three exceptions, each meaning something specific:

- **8px (`rounded-lg`)** on the outer container of a *card-like region* — stat
  cards and detail panels. A larger radius marks an enclosure rather than a
  control.
- **Full round** on things that are a *state* rather than an object: status
  badges, nav count badges, avatar initials, gate markers. The pill silhouette is
  itself a signal — if it is round, it is reporting something, not doing
  something.
- **4px (`rounded`)** on the in-row activation button, kept tighter than its
  6px neighbours so it never competes with the row's own edges.

Borders are always 1px. A border is either **Rule** (default, applied globally to
`*`) or **Rule Strong** (a border doing extra work). There is no 2px border
anywhere except the focus outline, which is a 2px solid ring in Ledger Ink at 2px
offset, applied via `:focus-visible` — visible for keyboard users, and never
suppressed.

### Named Rules

**The Pill-for-State Rule.** Full radius is reserved for elements reporting a
state. A fully-rounded button would be lying about what it is.

**The One-Radius Rule.** 6px unless there is a stated reason. Two radii inside one
component is a mistake, not a texture.

## Components

### Buttons

- **Shape:** 6px radius (`0.375rem`), 36px tall at default, 32px compact, 32px
  square for icon-only. Horizontal padding 14px / 10px. Icons are always 16px and
  never shrink.
- **Primary:** Ledger Ink ground, inverse text. Reserved for the one action a
  screen exists to perform.
- **Outline (the default variant):** Paper ground, hairline border, ink text. Most
  buttons in the console are this — a page with three equally-weighted primary
  buttons has no primary action.
- **Ghost:** no ground until hover, when it takes the Hover token. Icon controls
  and toolbar actions.
- **Danger:** the danger `-fg` hue as a solid ground with white text (the
  `-bg` token in dark mode). Reserved for genuinely destructive actions so the
  colour keeps its meaning.
- **Hover / Focus:** hover is a colour transition only — `transition-colors`, no
  transform, no lift. Focus is the global 2px `:focus-visible` ring.
- **Disabled:** 50% opacity and pointer-events off. A `501` deferred endpoint
  renders its action disabled with a tooltip, never as a live button.

*Target gap:* the chosen "confident and tactile" character asks for a real
`:active` state on buttons — a perceptible press. The incumbent build has none.

### Inputs / Fields

`components/ui/input` — `Input`, `Select`, `Textarea`.

- **Style:** Paper ground, 1px Input Rule border, 6px radius. Two sizes, matching
  the button scale so a field and the button beside it agree: `md` is 36px with
  10px padding, `sm` is 32px with 8px. Textarea is the same treatment with the
  caller's `min-height` and 8px vertical padding.
- **Label:** `FormField` renders a real `<label htmlFor>` and hands the control
  its `id` through context, so no call site owns the pairing and none can drift.
  Caption is Label type in Muted Ink, 4px above the control.
- **Error:** Micro type in the danger `-fg` hue, 4px below the control and
  **outside the label**. It carries `role="alert"` and its own id, which the
  control names in `aria-describedby`; the control also takes `aria-invalid`, and
  the red border keys off that same attribute rather than a separate class — so
  what a field looks like and what it announces cannot disagree. Server field
  errors from a `400` bind onto the same slot.
- **Focus:** `focus-visible` only, so a mouse click does not draw the keyboard
  ring. The ring shifts to the danger hue on an invalid field.

The error used to live *inside* the label. That folds it into the control's
accessible name, so a rejected field announced "Sum assured Must be at least
0.01" as its **name** — and kept announcing it after the user fixed the problem,
because a name is not a thing that changes. An error is a description.

*Resolved drift:* this treatment was a literal class string in **136 places across
24 variants**, with two of those variants quietly acting as a size scale. That is
why the ARIA wiring above lives in the component: as 136 hand-written attributes
it would never have been finished, and the parts that were would have drifted the
first time somebody copied a neighbouring field.

### Status Badge

The signature component. A fully-rounded pill, Label type, 2px/8px padding, a
tinted status ground with the matching same-hue foreground. One component maps
every enum on the platform through six buckets, keyed by domain because the same
literal genuinely means different things in different domains.

Three behaviours that are the component's whole point:

- An **absent** status renders an em dash in Subtle Ink — never a grey pill
  reading "Unknown", which would imply the backend said something when it said
  nothing.
- An **unrecognised** literal renders in its fallback bucket *plus* an inset
  Rule-Strong ring, a trailing `?`, and a title attribute naming the literal. A
  newly-added backend enum becomes visible on screen instead of silently
  defaulting.
- Colour is never the sole carrier: the humanised literal is always present as
  text.

### Tables

- **Header:** Label type in Muted Ink, 16px/10px padding, left-aligned, on the
  Band ground (`0.965`, ~5:1 for its ink). The band's tone is the edge, so there
  is no rule beneath it. Each header cell casts a 1px band-coloured shadow to its
  right, covering the seam Chrome leaves between cells at fractional widths.
- **Rows:** 44px, hairline separated, last row unruled. A cell whose value
  answers a question about the row (a premium's cover period, when and how it
  was paid) stacks it under the figure in Label type rather than taking a
  column. Right-aligned and `nowrap` columns never wrap. Hover takes the Hover
  token; the row previewed in the drawer takes Selected and `aria-current`.
- **Numerics:** right-aligned, always. Combined with global tabular figures, a
  money column is a true column.
- **Activation:** the first cell carries a real `<button>` filling the row height,
  so the row is keyboard-reachable and announced as one action. Never a click
  handler on a `<tr>`.
- **Secondary columns** are hidden below `sm`; the identifying column never is.
- **Pager:** mounted only for the four genuinely paged endpoints. Reads
  `"1–20 of 143"` in Label type with `aria-live="polite"`, and two ghost icon
  buttons. The eleven bare-array endpoints get the table and no pager, because a
  pager over a fully-downloaded array lies about the network.

### Panel

The titled section every detail page is built from. 8px radius, Paper ground,
hairline outline, and an unruled header block (16px sides, 12px top, 8px bottom)
carrying an `<h2>`, an optional Label-type subtitle, and optional `actions` on
the right for controls that act on the whole panel, such as a download. Content
brings its own padding, because a panel wraps two different kinds of thing — a
`<dl>` of `Field` rows, and a full-bleed table whose rows must reach the panel's
edges.

`Field` rows are not ruled. Label left, value right, on one baseline, 8px above
and below; a note takes its own line under the label, left-aligned, in Micro and
Subtle Ink. A rail with a rule under every field read as lined paper. Two fields
that would both be empty with an explanation each collapse into one ("Term and
maturity: None on record").

`emphasis` promotes exactly one panel per page to the Title tier with a
Rule-Strong edge. See **The Acting-Panel Rule** for when that is earned. It is
achromatic by law: a status tint here would make colour mean hierarchy, and colour
on this platform means state.

### Counts

**One number is a sentence; several are a row.**

- **`CountLine`** — a register's total, inline in `PageHeader` under the
  description, in Micro. The figure takes full-strength ink and tabular figures;
  the hint that says what was counted stays in Subtle Ink after a middot: "**67**
  clients · in this tenant", "**12** pending clients · matching this filter". Its
  label is phrased to follow a number, which is why the old card caption "All
  clients" became "clients".
- **`StatCards`** — a wrapping row of cards, 8px radius, Paper ground, hairline:
  Label caption, Display figure, Micro hint. Interactive cards are real
  `<button>`s with `aria-pressed` and take a Rule-Strong border when selected. Only
  for screens with more than one count, which today means one screen.

**Counts only, and no trend arrows** — there is no analytics endpoint on this
platform, so a card reading "↗ 12% wk/wk" would have nothing behind it. A null
value with `pending` shows a spinner; a null value without it shows an em dash
labelled "Not available", because a spinner that spins forever next to an error
panel is worse than an honest dash. Both components carry those three states, and
neither is a live region: the pager below the table already announces the same
total politely, and two regions reading out one number is worse than one.

### Navigation

Sidebar items are Body type in Muted Ink at 6px/8px with a 6px radius and a 16px
leading icon; active takes the Selected ground, full-strength ink and weight 500.
Group captions are Eyebrow type in Subtle Ink. A count badge is a fully-rounded
Selected-ground pill in Micro type, carrying both a `title` and screen-reader
text naming what was counted — because "3" alone reads as "3 claims", which is
not what it means.

Groups gate per-group, not per-item, and a group with no visible items renders
nothing at all.

**A badge belongs to the list beside it.** Where one group holds two items that
are two filtered views of the same endpoint — the client register's Individuals
and Corporate & groups — each item counts its own backlog. A single combined
count on one of them would promise rows that are behind the other item, and the
click would land on a shorter list than the number said.

**Two items may share a component, never a path.** Active state is derived from
the path, so two nav items differing only by a query parameter would both read as
active and the sidebar would stop answering "where am I". Each area gets a real
route, and its page `<h1>` names it too — the sidebar is not the only thing that
should say which of two similar lists you are looking at.

### Slide-over (Signature)

The right-hand panel is the "preview" half of **drawer previews, page acts**. Up
to 28rem wide, full height, Paper ground, hairline left rule, floating-layer
shadow, over a 20%-black backdrop. Header is a ruled block with Title, Micro
subtitle and a 28px close control; body scrolls at 20px/16px; the footer is a
ruled strip holding the link to the full page.

It slides in from the right over 200ms and fades its backdrop. It is dismissable
by clicking the backdrop, and therefore **must never host a mutating action** —
settlement decisions, waivers and payouts belong on a page a person navigated to
on purpose.

### Gate Panel (Signature)

The component that makes this an insurance console rather than a CRUD app.
Renders a set of business preconditions as a bordered 6px region whose ground
takes the danger tint if any hard gate fails, the warning tint if only soft gates
fail, and Quiet Paper if all pass. Each gate is a 16px round marker (✓ or !) in
the inverted status pair, a Body-weight-500 title, and a Muted-Ink Body
explanation. Every gate carries screen-reader text stating its outcome, because
the marker is colour and glyph only. An empty gate set renders nothing — the
panel appears when there is something to say, never as an empty frame.

### Confirm Act (Signature)

The second, deliberate click in front of an action that cannot be taken back.
Shares the Gate Panel silhouette — a bordered 6px region, danger tint when money
leaves or a benefit is refused, Quiet Paper otherwise — because it is the same
idea pointed the other way: the gate says what must be true before, this says
what becomes true after.

**Inline, never a modal.** A dialog that closes on a backdrop click is the wrong
shape for "move money", and inline keeps the values being committed on screen
above it, so the operator re-reads the real thing rather than a summary of it.
Nothing traps focus, so it is announced as a named `group` — calling it a dialog
would describe an interaction the user is not in.

Three lines, in order: the question, the consequence **with the real values**
("Pay TZS 1,240,000.00 to Juma Senior"), and what happens if this is wrong. The
third is a required prop, not an optional one — writing the confirmation forces
someone to have answered it.

**The confirming button carries the verb, never "Confirm" or "Yes", and never
the arming button's own words.** Two identical-looking buttons a click apart is
how the second click becomes as automatic as the first.

**The consequence must be true of the backend.** This is the Gate Panel doctrine
— *assert only what the platform can prove* — applied to outcomes. Where
something is genuinely recoverable the copy says how, so that the warnings on the
actions with no way back keep their meaning. A confirmation that cries wolf is
worse than none, because it teaches the room to click through them.

### Receipt

What happened, left where the form was. Success ground and `-fg` ink, a check
glyph, then label/value pairs and an optional note. Announced as a `status`, not
an `alert`: a result is not a problem.

**Persistent, not a toast.** A toast is gone in four seconds and cannot be
re-read, copied or screenshotted for a file note. Where being wrong costs money,
the record of what was done outlives the moment.

**Only facts the server returned.** The lines come from the response the caller
actually got; anything the client merely knows goes in the note, worded so the
difference is visible. A receipt with no lines is the honest rendering of a `202`
with no body, not a gap to fill with invented fields.

Shown only where the screen does not otherwise reveal the outcome — a settlement,
whose consequence is an invisible policy closure, and a payout, which answers
`202` with nothing. Waiving, reinstating and a KYC decision each change a status
badge already on the page, and a receipt restating it would be a second copy of
the same fact.

### Loading, Empty and Error States

One shared set, so every screen fails identically. Skeleton rows are Quiet-Paper
pulses at staggered widths so a table does not jump when data lands. Empty states
are centre-stacked with a 20px Subtle-Ink icon, a Body-weight-500 title and a
Micro description. Error panels are the same silhouette with a danger-hue icon
and copy written per error kind — a `403` is a permission boundary and offers no
retry, a `404` may be a disguised denial and never promises absence, a `501`
states the operation is not implemented and offers no retry. A `traceId` renders
in Mono in Subtle Ink with `select-all`, because it is the only thread back to
the backend logs.

### Motion

Almost none, deliberately. `transition-colors` on interactive surfaces, a 200ms
slide-and-fade on the slide-over, a spinner, and a skeleton pulse. That is the
complete motion vocabulary. Nothing eases in on scroll, nothing staggers, nothing
parallaxes.

## Do's and Don'ts

### Do:

- **Do** define every new colour twice — in `:root` and in `.dark`. A token that
  exists in one theme is unfinished.
- **Do** map a new backend enum onto one of the six existing status buckets, and
  take the literal from the OpenAPI spec or the Java enum rather than typing it
  from memory.
- **Do** right-align every numeric column and leave the global `tabular-nums`
  alone.
- **Do** align anything meeting the page edge to the 24px gutter.
- **Do** put a wide table in its own `overflow-x` container so the page body never
  scrolls sideways.
- **Do** make an activatable row a real `<button>` in the first cell, filling the
  44px row height.
- **Do** render an absent value as an em dash in Subtle Ink, and label it for
  screen readers when the absence is meaningful.
- **Do** give an icon-only control an `aria-label`, and give any badge or marker
  that carries meaning by colour a screen-reader text equivalent.
- **Do** use the outline button as the default and reserve Ledger Ink for the one
  action the screen exists to perform.

### Don't:

- **Don't** hard-code a hex, an `oklch()` or an `rgb()` in a component. Every
  colour is a token; the sole sanctioned exception is the hashed avatar hue,
  which is computed from an identifier.
- **Don't** add a seventh status bucket, or use a status hue for anything that is
  not a status.
- **Don't** add a trend arrow, sparkline, percentage delta or any aggregate this
  platform has no endpoint for. Counts only.
- **Don't** put a mutating action inside the slide-over. If it changes money,
  cover or a case, it belongs on a page.
- **Don't** put a mutating action in the 320px record rail either, and don't lead
  a work column with a panel whose height is unbounded — the ledger goes after
  the contract terms, or it buries them.
- **Don't** reach for a stat card to show one number. One count is a `CountLine`
  in the page header; `StatCards` is for numbers a person compares.
- **Don't** emphasise more than one panel on a page, and never with a status
  tint. If two panels are both the point, neither is.
- **Don't** give anything a resting shadow. Shadow means "floating above the
  page", and the only floating things are the slide-over and popovers.
- **Don't** introduce a third display size on a screen. Two peaks: the page
  headline, and the stat figure.
- **Don't** use uppercase for anything but a group caption.
- **Don't** suppress the `:focus-visible` outline, on any control, ever.
- **Don't** introduce a tenant name, tenant switcher or per-insurer branding.
  Tenancy is invisible by design and the console is white-label by requirement.
- **Don't** render a grey "Unknown" pill for a missing status, or a spinner for a
  value whose load has already failed.
- **Don't** copy the input class string into a new file. It is already duplicated
  past seventy times; the next one should be extracting the component, not adding
  to the count.
