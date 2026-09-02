---
name: Life Platform Console
description: A ledger-sober, vendor-neutral operating console for life insurance administration — ink on paper, hairline rules, and colour reserved for state.
colors:
  ink: "oklch(0.16 0 0)"
  ink-inverse: "oklch(0.99 0 0)"
  paper: "oklch(1 0 0)"
  paper-muted: "oklch(0.985 0 0)"
  muted-ink: "oklch(0.52 0 0)"
  subtle-ink: "oklch(0.63 0 0)"
  rule: "oklch(0.918 0 0)"
  rule-strong: "oklch(0.85 0 0)"
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
    fontSize: "0.6875rem"
    fontWeight: 400
    lineHeight: 1.4
  eyebrow:
    fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"
    fontSize: "0.6875rem"
    fontWeight: 500
    lineHeight: 1.4
    letterSpacing: "0.03em"
    textTransform: "uppercase"
  mono:
    fontFamily: "ui-monospace, 'SF Mono', 'Cascadia Mono', Menlo, monospace"
    fontSize: "0.6875rem"
    fontWeight: 400
    lineHeight: 1.4
rounded:
  xs: "0.25rem"
  sm: "0.25rem"
  md: "0.375rem"
  lg: "0.5rem"
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
  button-outline:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "0 14px"
    height: "36px"
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
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "0 10px"
    height: "36px"
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
    padding: "6px 8px"
  nav-item-active:
    backgroundColor: "{colors.selected}"
    textColor: "{colors.ink}"
  sidebar:
    backgroundColor: "{colors.paper-muted}"
    width: "224px"
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
- Hairline rules and tonal surfaces carry all structure — no shadows at rest
- Tabular figures globally, so every money and date column reads as a column
- A compressed, functional type scale that prizes density over display
- Dark mode is a first-class peer, not a filter: every token is defined twice
- Vendor-neutral and tenant-invisible by construction

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
- **Subtle Ink** (`oklch(0.63 0 0)`): the quietest legible tier — the em dash that
  stands for an absent value, nav group captions, field notes, trace IDs.
- **Rule** (`oklch(0.918 0 0)`): the hairline. Applied globally to `*` as the
  default border colour, so any element that grows a border grows the right one.
- **Rule Strong** (`oklch(0.85 0 0)`): a rule that is doing more work — a selected
  stat card, a hovered interactive card, the ring around an unrecognised status.
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
measured distribution is 226 uses of 0.75rem, 98 of 0.875rem, 94 of 0.6875rem,
16 of 0.625rem, and exactly one each of the two largest sizes. Read that as the
real hierarchy: **0.75rem is the workhorse of this console**, and 0.875rem is
reserved for the values a person actually reads a row to find.

- **Display** (600, 1.5rem, tracking-tight): the stat-card figure, and nothing
  else. One occurrence in the codebase, by design.
- **Headline** (600, 1.25rem, tracking-tight): the page `<h1>` in `PageHeader`.
  One occurrence, because one component owns it.
- **Title** (600, 1rem): the slide-over title and the heaviest in-panel headings.
- **Body** (400, 0.875rem): the *content* tier — table cell values, form control
  text, buttons, nav items, gate titles. Reserved for what carries the answer.
- **Label** (500, 0.75rem): the **most-used size in the system by a wide margin**,
  and the true default for chrome — table headers, field labels, descriptions,
  stat labels, form captions, most secondary cell content.
- **Micro** (400, 0.6875rem): notes, error messages, badge counts, trace IDs,
  stat hints. The intended floor.
- **Eyebrow** (500, 0.6875rem, +0.03em, uppercase): sidebar group captions and
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
page headline and, on list screens, the stat figure. A screen that grows a third
display size has invented a hierarchy the system does not have.

**The Uppercase-Is-Structure Rule.** Uppercase marks a navigational or sectional
group caption. It is never used for a button, a badge, a status, or emphasis.

## Layout

**The shell.** A fixed 224px sidebar in Quiet Paper with a hairline right rule,
against a scrolling main column. The sidebar holds a wordmark block, the
role-gated nav groups, and a user block pinned to the bottom by a top rule. Nav
groups are ordered along the business flow — Clients, New business, Policies &
claims, Finance, Distribution, Records, Configuration — with configuration last
because authoring a product is rare set-up rather than daily work.

**The page rhythm.** A 24px page gutter governs everything: the header block
(24px sides, 24px top, 16px bottom), the stat row (24px sides, 20px bottom), and
the content beneath. Table cells step inward to 16px; slide-over content sits at
20px. These four values — 24 / 20 / 16 / 12 — are the spacing spine, and 8px and
6px handle intra-component gaps.

**Density.** One comfortable row density, fixed: table rows are 44px tall, which
is also the minimum comfortable pointer target, so row height and hit target are
the same decision. Controls come in two heights — 36px for a primary control,
32px for a compact or in-table one.

**Stat row.** Two columns below `lg`, four at `lg` and above, 12px gaps.

**Responsive posture.** Desktop-first and honest about it: the console is built
for a back-office desk and a large monitor. The only breakpoint doing real work
is `sm`, which reveals table columns marked `secondary` — columns that are useful
but not identifying, hidden on narrow viewports so the identifying column always
survives. The sidebar does not currently collapse. **This is the least-developed
part of the system**, and any narrow-viewport work is new design rather than a
rule to be looked up here.

**Overflow.** A wide table scrolls inside its own `overflow-x` container. The
page body never scrolls horizontally.

### Named Rules

**The 24 Rule.** The page gutter is 24px, and every element that meets the page
edge aligns to it — header, stat row, empty state, error panel. An element that
sets its own page margin has broken the ledger's ruling.

**The 44px Row Rule.** A table row is 44px and the activation control fills it.
Row height and touch target are one number, so they cannot drift apart.

## Elevation & Depth

**This system is flat, and that is law.** Structure is carried entirely by
hairline rules and, in dark mode, by a two-step tonal relationship between the
page ground and panel surfaces. Thirty container instances across the codebase
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
option, and there is no such thing as a subtle resting elevation here.

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

- **Style:** Paper ground, 1px Input-token border, 6px radius, 36px tall (32px in
  compact contexts), 10px horizontal padding. Textareas use the same treatment
  with a `min-height` and 8px vertical padding.
- **Label:** `FormField` wraps the control in a `<label>` so association is
  implicit and no `htmlFor`/`id` pair can drift. Caption is Label type in Muted
  Ink, 4px above the control.
- **Error:** Micro type in the danger `-fg` hue, 4px below the control. Server
  field errors from a `400` bind onto the same slot.
- **Focus:** the global ring; no border colour change, no glow.

*Known drift:* this treatment is copy-pasted as a literal class string in **over
70 places** and exists as no shared component. It is a system rule in practice
and an accident waiting to happen in fact.

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

- **Header:** Label type in Muted Ink, 16px/10px padding, left-aligned, one
  hairline beneath the row.
- **Rows:** 44px, hairline separated, last row unruled. Hover takes the Hover
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

### Stat Cards

Two-up then four-up, 8px radius, Paper ground, hairline. Label caption, Display
figure, Micro hint naming exactly what was counted. Interactive cards are real
`<button>`s with `aria-pressed` and take a Rule-Strong border when selected.

**Counts only, and no trend arrows** — there is no analytics endpoint on this
platform, so a card reading "↗ 12% wk/wk" would have nothing behind it. A null
value with `pending` shows a spinner; a null value without it shows an em dash
labelled "Not available", because a spinner that spins forever next to an error
panel is worse than an honest dash.

### Navigation

Sidebar items are Body type in Muted Ink at 6px/8px with a 6px radius and a 16px
leading icon; active takes the Selected ground, full-strength ink and weight 500.
Group captions are Eyebrow type in Subtle Ink. A count badge is a fully-rounded
Selected-ground pill in Micro type, carrying both a `title` and screen-reader
text naming what was counted — because "3" alone reads as "3 claims", which is
not what it means.

Groups gate per-group, not per-item, and a group with no visible items renders
nothing at all.

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
