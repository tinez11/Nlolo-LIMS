---
name: Life Platform Console
description: A vendor-neutral operating console for life insurance administration — white record cards on a cool page, navy ink, and one teal that marks the act and lights the answer.
colors:
  navy-ink: "oklch(0.22 0.03 230)"
  ink-inverse: "oklch(0.99 0 0)"
  tide-teal: "oklch(0.52 0.11 190)"
  mist-page: "oklch(0.975 0.006 200)"
  card-white: "oklch(1 0 0)"
  quiet-mist: "oklch(0.965 0.006 200)"
  muted-ink: "oklch(0.5 0.025 230)"
  subtle-ink: "oklch(0.53 0.02 230)"
  rule: "oklch(0.915 0.01 210)"
  rule-strong: "oklch(0.85 0.012 210)"
  input-edge: "oklch(0.66 0.015 220)"
  control: "oklch(0.96 0.008 200)"
  control-hover: "oklch(0.935 0.01 200)"
  control-press: "oklch(0.9 0.012 200)"
  band: "oklch(0.965 0.008 200)"
  hover-tint: "oklch(0.96 0.022 190)"
  selected-tint: "oklch(0.935 0.04 190)"
  harbour-navy: "oklch(0.22 0.035 230)"
  harbour-navy-raised: "oklch(0.3 0.04 228)"
  harbour-ink: "oklch(0.95 0.01 200)"
  harbour-muted: "oklch(0.74 0.02 220)"
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
  headline:
    fontFamily: "'Sora Variable', Sora, 'Manrope Variable', ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.75rem"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "-0.02em"
  display:
    fontFamily: "'Manrope Variable', Manrope, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "-0.015em"
    fontFeature: "tnum"
  figure:
    fontFamily: "'Manrope Variable', Manrope, ui-sans-serif, system-ui, sans-serif"
    fontSize: "clamp(1rem, 7cqi, 1.5rem)"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "-0.015em"
    fontFeature: "tnum"
  title:
    fontFamily: "'Sora Variable', Sora, 'Manrope Variable', ui-sans-serif, system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: "-0.005em"
  body:
    fontFamily: "'Manrope Variable', Manrope, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.45
    fontFeature: "tnum"
  label:
    fontFamily: "'Manrope Variable', Manrope, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 500
    lineHeight: 1.4
  micro:
    fontFamily: "'Manrope Variable', Manrope, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 400
    lineHeight: 1.4
  eyebrow:
    fontFamily: "'Manrope Variable', Manrope, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 500
    lineHeight: 1.4
    letterSpacing: "0.03em"
  mono:
    fontFamily: "'Manrope Variable', Manrope, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 400
    lineHeight: 1.4
rounded:
  sm: "8px"
  md: "10px"
  lg: "12px"
  xl: "16px"
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
    backgroundColor: "{colors.tide-teal}"
    textColor: "{colors.ink-inverse}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "0 14px"
    height: "36px"
  button-secondary:
    backgroundColor: "{colors.control}"
    textColor: "{colors.navy-ink}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "0 14px"
    height: "36px"
  button-secondary-hover:
    backgroundColor: "{colors.control-hover}"
  button-outline:
    backgroundColor: "{colors.card-white}"
    textColor: "{colors.navy-ink}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "0 14px"
    height: "36px"
  button-ghost-hover:
    backgroundColor: "{colors.hover-tint}"
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
  input-text:
    backgroundColor: "{colors.control}"
    textColor: "{colors.navy-ink}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "0 10px"
    height: "36px"
  status-badge:
    typography: "{typography.label}"
    rounded: "{rounded.full}"
    padding: "2px 8px"
  card:
    backgroundColor: "{colors.card-white}"
    rounded: "{rounded.xl}"
    padding: "16px"
  figures-band:
    backgroundColor: "{colors.tide-teal}"
    textColor: "{colors.ink-inverse}"
    typography: "{typography.display}"
    rounded: "{rounded.xl}"
    padding: "20px"
  record-tab:
    backgroundColor: "{colors.control}"
    textColor: "{colors.muted-ink}"
    typography: "{typography.body}"
    rounded: "{rounded.lg}"
    padding: "0 14px"
    height: "32px"
  record-tab-active:
    backgroundColor: "{colors.card-white}"
    textColor: "{colors.navy-ink}"
  table-header-cell:
    backgroundColor: "{colors.band}"
    typography: "{typography.label}"
    textColor: "{colors.muted-ink}"
    padding: "10px 16px"
  table-row:
    backgroundColor: "{colors.card-white}"
    typography: "{typography.body}"
    height: "44px"
  table-row-hover:
    backgroundColor: "{colors.hover-tint}"
  table-row-selected:
    backgroundColor: "{colors.selected-tint}"
  sidebar:
    backgroundColor: "{colors.harbour-navy}"
    textColor: "{colors.harbour-ink}"
    width: "240px"
  nav-item:
    textColor: "{colors.harbour-muted}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "8px 10px"
  nav-item-active:
    backgroundColor: "{colors.harbour-navy-raised}"
    textColor: "{colors.harbour-ink}"
  slide-over:
    backgroundColor: "{colors.card-white}"
    width: "28rem"
    padding: "16px 20px"
---

# Design System: Life Platform Console

## Overview

**Creative North Star: "The Lit Figure"**

Every record in this console is a white card laid on a cool, quiet page, read under a
navy margin. Each card says what it is in navy ink, and the one figure a person opened
it for — the premium a life pays, what a policy assures, what a deposit is worth at
maturity — is lit in teal. Nothing else is lit. A clerk scanning forty records a day
should find the answer on each one without looking for it, and should never mistake a
decoration for the answer, because there are no decorations.

The direction was adopted on 2026-10-21 from the *tinezz-insurance-console* mockup
(Lovable, in `sample data/`) and replaced the achromatic "Ledger". It keeps everything
the Ledger proved about working software for a back office — tabular figures, the six
status buckets, contrast floors, the record rail, confirmations that state real values,
receipts that state only returned facts — and changes the material: from ink on paper
to cards on a page, from one ink colour to navy and one teal, from Inter to Manrope and
Sora. **Render only what the platform can prove** still governs: the lit figure is
always a value the server returned, never a total the browser made up.

Character is **calm, confident and legible at a glance**. The page is cool and nearly
white so cards read by their lift rather than by lines; the sidebar is a deep navy
island so the work area is never confused with navigation; the teal is rationed so it
keeps its two meanings. The console is still emphatically not glassy, not playful and
not gradient-lit outside the one figures band, and it still carries no insurer's brand:
the teal and the LP mark belong to the platform, never to a tenant.

**Key Characteristics:**

- Cool mist page, white cards on a soft two-layer shadow, a navy sidebar island
- One teal with two meanings: *act here* (primary action, focus, mark) and *this is the answer* (the figures band)
- Six status hues, unchanged, are the only other colour
- Manrope for every working line and every number, tabular; Sora only for heading words
- A compressed, functional type scale; density over display
- Light and dark defined for every token; the sidebar is navy in both

## Colors

A cool, near-neutral page and card system in navy ink, with one saturated teal and six
low-chroma status hues.

### Primary

- **Tide Teal** (`tide-teal`): the primary button ground, the focus ring, the LP mark,
  the active outline in a form's section list, and the ground of the figures band (as a
  gradient deepening toward navy). White on it measures ~5.5:1. In dark mode it lifts
  to `oklch(0.72 0.11 190)` and carries navy ink instead of white. Hover and Selected
  are faint teal tints of the page (`hover-tint`, `selected-tint`), so a hovered row and
  a chosen one read as the same family, Selected one step deeper.

### Neutral

- **Navy Ink** (`navy-ink`): body text, headings, every value. Navy rather than black,
  so the page reads as one temperature.
- **Mist Page** (`mist-page`): the page ground behind cards. Faintly teal-tinted so a
  white card is visible on it by tone alone.
- **Card White** (`card-white`): every card, panel, table and slide-over.
- **Quiet Mist** (`quiet-mist`): skeleton fills and the quietest grounds.
- **Muted Ink** (`muted-ink`): labels, descriptions, inactive tabs, table headers —
  ~4.7:1 on the page, ~5:1 on a card.
- **Subtle Ink** (`subtle-ink`): field notes, trace ids, the em dash that means
  "absent". 12px is its floor, and it holds ~4.5:1 there.
- **Rule** (`rule`) and **Rule Strong** (`rule-strong`): a card's hairline edge, row
  rules, an emphasised panel's edge.
- **Input Edge** (`input-edge`): the edge of every field, deliberately darker than
  Rule — a field's edge is the only thing saying "you can type here", and WCAG 1.4.11
  asks 3:1 of it.
- **Control** / **Control Hover** / **Control Press** and **Band**: the filled ground
  of fields and secondary buttons, its two interaction steps, and the table header band.

### The sidebar island

- **Harbour Navy** (`harbour-navy`), **Harbour Navy Raised** (`harbour-navy-raised`),
  **Harbour Ink** (`harbour-ink`), **Harbour Muted** (`harbour-muted`): the sidebar
  redefines the same token names inside `.sidebar-scope` rather than growing
  sidebar-only utilities, so the nav, its count badges, the search button and the user
  block keep their classes and resolve navy there. In dark mode the island goes darker
  than the page (`oklch(0.14 0.025 235)`), so it still reads as a margin.

### The six status hues

Unchanged from the Ledger, and still the semantic vocabulary for ~25 backend enums:
**Slate** neutral, **Periwinkle** pending, **Teal** active, **Moss** success, **Amber**
warning, **Rust** danger — each a tinted ground with a same-hue foreground, inverted in
dark mode with the hue order preserved.

*Open (2026-10-21):* the **active** bucket's teal sits close to Tide Teal, so an
"Active" badge can read as brand colour. Moving active to a green was proposed and not
yet decided.

### Named Rules

**The Two Meanings Rule.** Teal means *act here* or *this is the answer*, and nothing
else. It never marks a status (the six buckets do that), never brands a section, and
never fills a decorative surface. A screen with no action and no headline figure has
almost no teal on it, and that is correct. The one exception is the chart palette's first
series (below), a deeper teal-green that is a data colour, not the accent.

**The Chart Palette Rule (2026-10-23, the user).** Navy-and-grey charts read as "not good
colour-wise"; charts now take five series colours, `--chart-1` to `--chart-5`, in that order
and never cycled: a deep teal-green, blue, amber, violet, coral. The set was validated as one
(lightness band, chroma floor, colour-blind separation between neighbours, 3:1 on the
surface), light and dark each with its own steps. A series is an identity, never a state;
every series is named in a legend or a list beside the chart, so no reading rests on colour
alone. A dashboard figure's change is green when it is good news and red when it is bad,
with an arrow and the words "up" or "down" -- the Stamp Rule still holds for statuses.

**The Six Buckets Rule.** Six status buckets are the complete status vocabulary. A new
backend enum maps onto an existing one; one that fits none is a product conversation.

**The Both Themes Rule.** Every colour is a token in `:root` and again in `.dark`, and
every island (`.sidebar-scope`, `.hero-scope`) redefines token names rather than
hard-coding values. Inside an island, use the raw token (`var(--surface-muted)`), never
the `--color-*` alias: the aliases resolve once at the root and would paint the page's
colours inside the island.

## Typography

**Display Font:** Sora Variable (self-hosted), for heading words only
**Body Font:** Manrope Variable (self-hosted), for every working line and every number
**Mono Font:** Manrope with tabular figures — machine identifiers only. Not a monospace: the system one fell to a Courier on Windows and clashed with the console's face.

**Character:** Manrope is a calm, open grotesque with clear figures; Sora gives the page
title and panel titles a slightly geometric voice, so a heading reads as a heading
before it is read. Both are self-hosted variable files (`@fontsource-variable`), so a
white-label console makes no third-party font request and every weight is real.

### Hierarchy

- **Headline** (Sora 600, 1.75rem on `md` and up, 1.5rem below, −0.02em): the page
  `<h1>` in `PageHeader`, and nothing else. One step down on a phone because the page
  bar is sticky and a 28px title wrapping to two lines held a third of the screen.
- **Display** (Manrope 600, 1.5rem, tabular): the lit figure — the figures band, the
  quotation headline, a stat figure.
- **Figure** (Manrope 600, `clamp(1rem, 7cqi, 1.5rem)`): a KPI figure inside a card that
  declares `@container`. It is the Display size when the card has room and shrinks with
  the card below that, so a 21-character TZS amount never leaves its card.
- **Title** (Sora 600, 1rem): an emphasised panel heading, the slide-over title.
- **Body** (Manrope 400, 0.875rem): values, table cells, controls, nav.
- **Label** (Manrope 500, 0.75rem): field labels, table headers, captions — the most used size.
- **Micro** (Manrope 400, 0.75rem): notes, errors, trace ids, badge counts. The floor.
- **Eyebrow** (Manrope 500, 0.75rem, +0.03em, uppercase): sidebar group captions and form
  section legends. The only uppercase.

### Named Rules

**The Manrope Figures Rule.** Every number — money, dates, counts, rates — is set in
Manrope with tabular figures. Sora is for words in headings; a page title that is an
identifier (a policy number) is the one place digits appear in Sora. A crawl of 46
screens on 2026-10-22 found no figure outside Manrope.

**The Tabular Rule.** `tabular-nums` is set on `body` and never overridden. Every figure
is meant to be compared against the one above it.

**The Twelve Floor Rule.** Nothing is set below 12px (guarded in
`designGuards.test.ts`). Hierarchy below Body is carried by ink and weight, not by size.

**The Two-Peaks Rule.** At most two sizes above Title on a screen: the page headline and
the lit figure.

**The Uppercase-Is-Structure Rule.** Uppercase marks a navigational or sectional group
caption, and only through the Eyebrow tier. Never a button, a badge or emphasis.

## Layout

**The shell.** A fixed 240px navy sidebar against a scrolling main column. The sidebar
holds the LP mark and wordmark, the search, the role-gated nav groups (ordered along the
business flow, configuration last) and the user block. Every destination has its own icon.

**The shell owns the viewport, and the page never scrolls.** The shell is
`fixed inset-0` and `main` is the one vertical scroller. A second document scrollbar
once lifted the whole shell off the viewport floor; nothing may reintroduce one.

**The page rhythm.** A 24px page gutter governs everything that meets the page edge.
Cards step inward to 16px; a form card to 24px; slide-over content to 20px. 24 / 20 /
16 / 12 is the spacing spine, 8 and 6 handle intra-component gaps.

**The page bar.** Sticky, carrying breadcrumb, headline, an optional status pill beside
the title and actions aligned to the title block's last line. It publishes
`--pagebar-h` so everything else that sticks stops under it.

**Density.** Table rows are 44px, which is also the pointer target. Controls are 36px,
32px compact, and step to 44px under `pointer-coarse`.

**The detail page.** `DetailLayout`: a `minmax(0,1fr)` work column and, where the page
has one, a 320px record rail, pinned only while it fits. The policy record has no rail
(2026-10-21): its facts lead the Overview tab, behind the figures band. Below `lg` the
rail falls after the work in DOM order.

**The form page.** `FormLayout`: the form on a card at up to 48rem and a 16rem rail
beside it, sticky at the page bar's height. The rail holds the form's own section list
(`FormOutline`, read from `data-outline` sections, drawn only from two sections up) and,
where the page derives one, a live figure (the group scheme so far). Below `lg` the rail
is hidden and the card is full width, so the rail may never hold anything the form does
not also show. A form whose sections are already `Panel`s takes `card={false}`.

**The quotation page.** Inputs left (up to 40rem), the quotation right (20–26rem),
sticky and bounded to the screen with its own scroll, the next step pinned to its foot.
One column on a phone, in the order of the conversation: person, plan, price.

**Responsive posture.** Desktop-first and honest about it. `sm` reveals `secondary`
table columns; below `md` the sidebar becomes a drawer (`invisible` when closed so it
leaves the tab order). A wide table scrolls inside its own container; the page body
never scrolls sideways (crawled at 1280px on 2026-10-22: none does).

### Named Rules

**The 24 Rule.** The page gutter is 24px, and everything that meets the page edge aligns to it.

**The Record-Rail Rule.** The 320px rail holds facts and never an action. A person reads
a narrow column as a summary, and an action read as a summary is taken without reading.

**The Acting-Panel Rule.** One panel per page may take `emphasis`, only where the page
exists to perform that act. Emphasis is the Title tier and a Rule-Strong edge, never a tint.

**The 44px Row Rule.** A table row is 44px and its activation control fills it.

## Elevation & Depth

**Cards float; what is inside a card does not.** Depth is a two-layer shadow under every
page-level box — a tight contact shadow and a wide soft one — on a page ground a step
darker than the card. Inside a card everything is flat: nested boxes take a hairline
and no shadow. Layers that genuinely float above the page (slide-over, popovers) keep a
stronger, separate shadow.

### Shadow Vocabulary

- **Card** (`box-shadow: 0 1px 2px oklch(0.2 0.03 230 / 5%), 0 8px 24px -12px oklch(0.2 0.03 230 / 12%)`):
  every page-level card — panels, KPI cards, list frames, form cards, the quotation.
  In dark mode a 1px light hairline plus a deeper ambient shadow.
- **Raise** (`box-shadow: 0 1px 2px oklch(0.2 0.03 230 / 6%), 0 2px 6px -2px oklch(0.2 0.03 230 / 10%)`):
  the active pill in a tab track or a two-way switch.
- **Floating layer** and **floating menu**: the slide-over and popovers, unchanged.

### Named Rules

**The Card Rule.** A page-level box is a card: 16px radius, Card White, hairline, Card
shadow. A box inside a card is flat: a smaller radius and a hairline, no shadow. A
shadow inside a card is a box in a box. (80 hand-built page boxes were converted on
2026-10-22; the inner `rounded-md border` boxes were deliberately left flat.)

**The One Band Rule.** The only gradient is the figures band: Tide Teal deepening toward
navy (`--hero`), under the lit figure. No other surface carries a gradient, and the band
holds figures, never controls.

**The Three Lines Rule.** Only three kinds of line: a box's outline, the faint rule
between table rows, and a form section's rule. A panel's title is not ruled off, a table
header band is not ruled off, a `Field` list is not ruled between fields. Control edges
keep their 3:1 Input Edge (WCAG 1.4.11).

## Shapes

A rounded, soft family keyed to one variable (`--radius: 0.625rem`):

- **10px** (`rounded-md`): controls — buttons, fields, nav items. The default.
- **12px** (`rounded-lg`): a pill inside a track (an active record tab, a switch side);
  a flat box inside a card.
- **16px** (`rounded-xl`): every card and the figures band; a tab track.
- **Full**: things that report a state — status badges, count badges, avatars, gate markers.

Borders are 1px, Rule or Rule Strong. The focus outline is a 2px Tide Teal ring at 2px
offset on `:focus-visible`, never suppressed; inside a clipped strip (record tabs) it is
drawn inside the control.

### Named Rules

**The Pill-for-State Rule.** Full radius is reserved for an element reporting a state.

**The Nesting Radius Rule.** A box inside a card takes a smaller radius than the card.
Equal radii nested read as a misalignment.

## Components

### Buttons

- **Shape:** 10px, 36px default, 32px compact, 32px icon-only; 14px / 10px padding;
  16px icons.
- **Primary:** Tide Teal ground, white label. The one act a screen exists for; every
  form's submit is Primary.
- **Secondary (default):** the filled Control ground, navy label, no border.
- **Outline:** Card White ground, Rule Strong edge — "Correct details" beside a teal
  "New case".
- **Ghost:** no ground until the teal Hover tint. Toolbars, Cancel, icon controls.
- **Danger:** Rust ground, reserved for genuinely destructive acts.
- **States:** hover and press are tone only (`active:duration-0` lands the press on
  pointer-down). `pending` shows a spinner and `aria-busy` and never renames the button.
  Disabled is 50% opacity.

### Inputs / Fields

- **Style:** Control ground, 1px Input Edge, 10px radius; on focus the ground turns
  Card White, the edge Rule Strong, with a teal ring at 40%.
- **Label and error:** `FormField` pairs a real `<label>` with the control; an error is
  Micro in the danger hue below the control, outside the label, wired by
  `aria-describedby` and `aria-invalid`. Forms validate when a field is left.
- **A refusal goes when it is corrected:** an `InlineError` from a submit clears on the
  first edit inside its form or `data-edit-scope`; a failed read is `sticky`.

### Cards and Panels

`Panel` is the titled card every record is built from: 16px radius, Card White,
hairline, Card shadow, a header of `<h2>` (Sora; Title tier when `emphasis`), optional
subtitle and actions. Content brings its own padding. `Panel outline` lists it in a
`FormLayout` rail. `Field` rows are label-left, value-right on one baseline; `Field
stacked` puts the label above a Display-tier value for the figures band.

### Figures Band (Signature)

The lit figure. A 16px teal-to-navy gradient band (`bg-hero` inside `.hero-scope`,
which redefines the inks to white at 82% and 78%), holding up to three Display figures
with their labels above and a Micro caption below.

- **Policy record:** sum assured, premium with its frequency, and the account or cash
  value, leading the Policy panel.
- **Quotation:** one `Headline` — the premium and frequency, "for TZS 5,000,000.00 of
  cover over 10 years"; a deposit's value at maturity; an account's rate; a family's
  payment. Its row is removed from the list beneath, so each figure appears once.
- **Only server figures.** The band shows what the server returned; a caption may
  restate the inputs the figure answers, never a computed total.

### Record Tabs and Switches

A tab track: Control ground, 16px radius, 4px inset, holding 32px pills of Body 500 in
Muted Ink; the active pill is Card White with the Raise shadow. The strip is sticky
under the page bar, full width and opaque so content never shows beside the track. A
two-way switch (Individual / Corporate, New person / Existing client) is the same pill
pair with `aria-pressed`.

### Status Badge (Signature)

A full pill, Label type, tinted ground and same-hue ink, mapping every enum through the
six buckets per domain. An absent status is an em dash; an unrecognised literal takes
its fallback plus a Rule Strong ring and a `?`. Colour never carries meaning alone.

### Tables

Header on the Band ground in Label Muted Ink, unruled; 44px rows on hairlines, hover the
teal Hover tint, the previewed row Selected with `aria-current`. Numerics right-aligned
and tabular. Activation is a real `<button>` in the first cell. Registers drop columns
nobody scans by — the client register shows name and KYC status, and the 36-character
party id lives on the record as "Client id".

### Dashboard and KPI cards

`StatTile` (the dashboards, 2026-10-23): an icon chip tinted in the figure's own series
colour, Label caption, Figure-tier value that never wraps, the change against the same
months a year earlier as a green or red pill with an arrow, and a sparkline of the range; a
link to exactly the list it counted, or not a link. A range picker sits once above the tabs
(this month, the quarter, the year to date, the last twelve months, custom months), in the URL.
Charts (`components/charts`, in-house SVG, no library): trend lines, columns (grouped or
stacked), rings with their list beside them, and labelled share bars; series in the chart
palette, never a status hue; every point the server's; the month in progress an outline; a
hover or arrow-key crosshair; one half-second entrance, none under reduced motion.

### Navigation (the sidebar island)

Navy island, 240px. Items are Body in Harbour Muted with a 16px icon at 10px radius;
active is Harbour Navy Raised, Harbour Ink, weight 500. Group captions are Eyebrow.
Count badges are full pills with screen-reader text naming what was counted. The LP
mark is a 36px Tide Teal tile — platform, never tenant.

### Slide-over, Gate Panel, Confirm Act, Receipt (Signatures)

Behaviour unchanged from the Ledger and still binding:

- **Slide-over** previews and never acts; dismissable, so it hosts no mutation.
- **Gate Panel** states preconditions before an act, tinted danger or warning.
- **Confirm Act** is the inline second click: the question, the consequence with real
  values, and the required reversal line; the confirming button carries the verb.
- **Receipt** is a persistent `status` of facts the server returned, only where the
  screen does not otherwise show the outcome.

### Quotation (Signature, 2026-10-22)

Inputs and price side by side, repricing as the agent types. The answer leads on the
figures band; details below; "How it was priced" folds behind its summary in front of a
customer and stays open on a staff form; rows that only echo the inputs are dropped
beside the inputs. A refusal is an inline strip in plain words. The PDF is offered only
on a priced quotation. "Proceed" is pinned in view at the foot of the column and offered
only on a quotation the server priced.

### Loading, Empty and Error States

One shared set: skeleton rows in Control tone, `LoadingBlock` with a visible label, empty
states centre-stacked, error copy per error kind, trace ids in the mono token with `select-all`.

### Motion

Almost none: colour transitions, an instant press tone, a 200ms slide-over, 150ms
popovers growing from their trigger, a spinner, a skeleton pulse. Under
`prefers-reduced-motion` travel and scale are zeroed and fades kept; under
`prefers-contrast: more` hairlines and secondary inks step stronger, in both themes.

## Do's and Don'ts

### Do:

- **Do** put every page-level box on a card (16px, Card White, hairline, Card shadow) and keep what is inside it flat.
- **Do** set every figure in Manrope with tabular figures, right-aligned in a column.
- **Do** lead a record with the figure it exists for, on the figures band, and only a figure the server returned.
- **Do** make the one act on a screen the Primary teal button, and every form's submit Primary.
- **Do** define every colour in `:root` and `.dark`, and inside an island use the raw token, not the `--color-*` alias.
- **Do** put a full-page form in `FormLayout`, and mark its sections with `data-outline` (or `Panel outline`).
- **Do** size a figure inside a card with the Figure tier, so it never leaves the card.
- **Do** map a new enum onto one of the six status buckets.
- **Do** render an absent value as an em dash in Subtle Ink.
- **Do** keep the 3:1 Input Edge on every field and the 2px teal focus ring on every control.
- **Do** check white text on any teal at 4.5:1 — a lighter teal measured 3.59:1 on the LP mark.

### Don't:

- **Don't** use the accent teal for a status, a section or decoration. It means *act here* or *this is the answer*; a chart series takes the chart palette.
- **Don't** put a shadow on a box inside a card, or a gradient anywhere but the figures band.
- **Don't** set a number in Sora, or any text below 12px.
- **Don't** hard-code a colour in a component; the hashed avatar hue is the only exception.
- **Don't** add a seventh status bucket, or colour a delta without its arrow and words.
- **Don't** sum, average or difference in the browser to make a figure look complete.
- **Don't** put a mutating action in the slide-over or the record rail.
- **Don't** emphasise more than one panel, or emphasise with a tint.
- **Don't** introduce a tenant name, switcher or per-insurer branding; the teal is the platform's.
- **Don't** load a font from a third party; both faces are self-hosted.
- **Don't** suppress `:focus-visible` on any control.

### Considered and declined

- **A category palette** (violet, plum, rose dots and tinted values on the Policy panel), 2026-10-21: built, seen, and reversed by the product owner on sight. Colour here carries action, answer and state, not category.
- **Raising content text from 12px to 14px** (2026-10-09). The compressed scale is a confirmed decision.
- **Translucent, blurred sticky bars**, **spring physics and drag-to-dismiss**, and **a receipt on every mutation** (2026-10-09).
- **The mockup's right-hand "Policy summary" rail** on the policy record: the policy page dropped its rail on 2026-10-21 and kept it dropped through the redesign. Not re-offered unless the product owner asks.
