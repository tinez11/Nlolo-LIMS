---
target: client overview + propose group scheme
total_score: 26
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 3
timestamp: 2026-11-06T06-56-39Z
slug: end-src-features-policies-issuegroupschemepage-tsx
---
Client Overview + Propose a group scheme critique (2026-11-06). Score 26/40.

| # | Heuristic | Score | Key issue |
|---|---|---|---|
| 1 | Visibility of system status | 3 | Live "scheme so far" is good; blank form already says "Not yet priced 1" |
| 2 | Match system / real world | 3 | Domain words right; "Note for the record" is unclear |
| 3 | User control and freedom | 3 | Cancel + unsaved guard; disabled sole-member remove unexplained |
| 4 | Consistency and standards | 2 | Three different empty-field looks; navy em dash breaks DESIGN.md; KYC "Pending" shown 3x |
| 5 | Error prevention | 3 | Required marked, products filtered; currency typed twice |
| 6 | Recognition rather than recall | 3 | Persistent labels, outline rail |
| 7 | Flexibility and efficiency | 2 | Members added one at a time, no paste/upload |
| 8 | Aesthetic and minimalist design | 2 | ~115 words of standing prose on the form; 240-492px row gaps |
| 9 | Error recovery | 3 | Errors wired and announced |
| 10 | Help and documentation | 2 | Help over-supplied inline, competes with fields |

P1 Overview rows split across wide cards: Field.tsx justify-between; label-to-value gaps 240-492px at 1440 (Phone 486, Email 492). Fix: two-column grid row (label 7-10rem, value left), numeric opt-in right-align, ClientOverview xl:grid-cols-3; amend DESIGN.md "label-left, value-right".
P1 Grey ladder collapsed: labels oklch .50 and hints .53 are 1.13:1 apart; placeholder (preflight 50% currentColor) 3.25:1 and only 1.63:1 off the label. Fix: --label .38 (500 weight) for labels and legends; hints stay .53; explicit --placeholder .62 on the input cva; values navy.
P1 Empty fields look different: PartyPicker white ground with 14px muted prompt reads as filled; DatePicker mask in muted ink; Input grey ground. Fix: one empty-field treatment (same ground, --placeholder prompt).
P2 Explanatory prose as standing UI: 9 blocks on the form (4 run ~120 chars/line), 4 on the Overview; money placeholders read as defaults. Fix: delete rationale, keep limits/formats/consequences in 3-5 words.
P2 Absent values in navy + duplicated status: em dash renders in --foreground against DESIGN.md:613; KYC Pending x3. Fix: <Absent/> in subtle ink; drop one Pending.

Detector: CLI 0 findings (verified with a positive control). Overlay: shadow-card token x6 (borderline FP), line-length x4 (real), nested-cards x2 (real), cramped outline rail (real), em-dash overuse (mostly empty-value dashes), layout-transition (FP, Donut stroke-width).
