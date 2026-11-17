---
target: Activity log page
total_score: 24
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 3
timestamp: 2026-11-15T14-15-54Z
slug: frontend-src-features-audit-activitylogpage-tsx
---
# Critique: Activity log (/staff/audit-log)

Method: dual-agent (A: design review, source-only; B: detector). Screenshots by parent after refreshing the admin sign-in.

## Design Health Score

| # | Heuristic | Score | Key Issue |
|---|---|---|---|
| 1 | Visibility of System Status | 3 | Stale list stays on screen during refetch; opened-line "Loading…" not announced |
| 2 | Match System / Real World | 2 | "Acted alone" appears as a System action; month-first dates, MM/DD/YYYY inputs, ISO dates in the count line |
| 3 | User Control and Freedom | 2 | One line open at a time; it closes on filter change; no link to one action; search text goes stale on Back/Forward |
| 4 | Consistency and Standards | 2 | Native date inputs instead of DatePicker; Find button vs instant filters; no column headings unlike DataTable pages |
| 5 | Error Prevention | 3 | Read-only; low risk |
| 6 | Recognition Rather Than Recall | 2 | Refusal reason only shows when the line is opened; amount has no heading; many lines name no record |
| 7 | Flexibility and Efficiency | 2 | No range presets, open-all, shortcuts or page size; CSV is the only power tool |
| 8 | Aesthetic and Minimalist Design | 3 | 16 controls before the first row; empty gap between the Who and Action columns |
| 9 | Error Recovery | 3 | ErrorPanel with retry throughout |
| 10 | Help and Documentation | 2 | System lines, kinds and the history start date are not explained |
| **Total** | | **24/40** | **Acceptable** |

## Design Specificity Verdict
The wording is authored for this product (hand-written past-tense labels, "Why it was refused:", "Only Asha Admin") and the page uses the shared kit. The structure is a generic filter bar over 25 paginated rows, where the user asked for a timeline. Detector: 0 findings in 7 files, confirmed real with --no-config and a positive-control file.

## Priority Issues
- [P1] "System" lines dominate (15 of 17 rows in the first screen); money events (Loan disbursed, Payout completed) and Case decided show no record; "Acted alone" is rendered as its own System action. Fix: show events under the action that caused them; name the job on real system lines; attach a record to every money event; show acted-alone as a marker on the person's action; add an actor filter (Staff/Agent/Customer/System). Command: clarify (needs a backend change first).
- [P1] The row shows the outcome but not the reason (outcomeWords() is used only in tests). Fix: show the reason as a muted second line on refused/failed rows; show server counts on the outcome chips. Command: clarify.
- [P1] It is a list, not a timeline: every row repeats a full month-first date; no day breaks; MM/DD/YYYY date inputs. Fix: sticky day-first day headings with time only on rows; range presets (Today/24h/7d/30d/Custom) with DatePicker for Custom; day-first dates in the count line; close the column gap. Command: layout.
- [P2] The filter bar is crowded: 16 controls; chip groups without a visible label or group role; Find button vs instant filters. Fix: label the Kind/Outcome groups; search on Enter and remove Find; turn a chip off by clicking it again instead of "All kinds"/"Any outcome". Command: distill, then harden.
- [P2] You can't keep or share your place: no ?line= link, one line open at a time, open line lost on filter change, search box out of step with the URL. Fix: put the open line in the URL; allow several open; add "Copy link to this action"; keep the search box in step with q. Command: harden.

## Persona Red Flags
- Alex (power user): no j/k, Enter or / shortcuts; no open-all; no page size; Find has to be clicked.
- Sam (keyboard/screen reader): each row reads as one run-on accessible name; the amount has no heading; chip groups have no names; nothing announced when an opened line loads or a filter changes; mobile grid order is confusing.
- Admin investigating an incident: "System" everywhere; must open lines one by one for refusal reasons; no link to one action; day boundaries fixed to +03:00 while todayIso() uses the local zone.

## Minor Observations
- The empty state always says "Nothing matches these filters", even with no filters on.
- "(staff)" after a name adds nothing in a staff-only console; show the role instead.
- On mobile the action label is truncated while the name gets full width (P3).
- Times under "What followed" repeat the full date.
- "Someone without a name" / "Someone (staff)": show the login name instead.
- The request body in an opened line is not masked; the backend scrubbing of secrets is unverified.

## Questions to Consider
1. Should the incident default be "Refused and failed, last 24 hours"?
2. Should each action have its own page, /staff/audit-log/<id>?
3. Has anyone decided that admins and management see unmasked ID and phone numbers in an opened line, and should opening one be logged?
