# Build 3 — product eligibility bounds

Status: **built 2026-09-03.** Third of six builds scoped from an underwriting
requirements review. Precondition for Build 4's `issueGates`. §7 records what
came out of building it.

---

## 1. What this adds

Six bounds on `product_version`: minimum and maximum entry age, minimum and
maximum term, minimum and maximum sum assured. Nothing consumes them yet —
Build 4 does. This build makes a product able to *state* what it will accept.

Today it cannot. `product_version` carries an effective date, a grace period, a
max loan-to-value and a surrender-charge schedule, and nothing about who or what
is eligible. So there is no way to refuse a proposal before it fails somewhere
further downstream, and no way to tell a user *why* in terms of the product.

## 2. Hard or soft: not one answer

The six bounds exist for different reasons, and treating them uniformly is the
mistake. Confirmed with the client. Build 4 implements the severities; they are
recorded here because they are properties of the bound, not of any product,
which is also why they live in gate code rather than as a column.

**Entry age — HARD.** This is arithmetic, not risk appetite. `base_rate_table`
is keyed on `(age_from..age_to, sex, smoker_status)`, and
`ProductApiImpl.quotePremium` already throws `PremiumNotQuotableException`
("No base rate for age N") when no cell matches — a 422 today. An out-of-range
age is therefore *already* unwritable; the gate invents no refusal, it surfaces
an existing one while the underwriter can still act on it rather than after the
form is filled. There is no business downside to blocking what cannot be priced.

**Term — HARD.** Term is staff-entered configuration, not an applicant
attribute. A 25-year term on a product designed for 20 is not a referral, it is
the wrong product; the answer is a new product version.

**Sum assured — SOFT, with a recorded reason.** This is the one where blocking
would cost real money. A sum assured above retention is not invalid business —
it is exactly what the reinsurance treaties this platform already models exist
to absorb. Hard-blocking would have the console refuse business the platform was
built to cede. The minimum is a commercial viability floor, so it is a nudge and
never a refusal.

**What makes a soft gate trustworthy.** A warning nobody records is a warning
staff learn to click past, which is worse than no gate because it launders the
decision. A soft breach must therefore be *named in the audit trail*:
`reasonForManualIssue` already exists on the issue request, is already required,
and already feeds audit. Build 4 makes a soft-breached gate require the reason to
say why. That needs no new override feature — the deferred underwriting-override
work (staff-portal audit gap #7) stays deferred.

The rule in one line: **a hard block you cannot override is a revenue risk; a
soft flag with no record is a compliance risk.** Hard only where the platform
genuinely cannot proceed; soft-with-recorded-reason everywhere else.

## 3. Schema — `product/V6__eligibility_bounds.sql`

| Column | Type |
| --- | --- |
| `min_entry_age` / `max_entry_age` | `INTEGER` |
| `min_term_months` / `max_term_months` | `INTEGER` |
| `min_sum_assured` / `max_sum_assured` | `NUMERIC(19,2)` |

All nullable — an unbounded dimension is a real product design, not a gap, and
every version published before this has none.

No currency column. The sum-assured bounds are read in the product's own
`default_currency` on `product_definition`; a second currency here could
disagree with it, and a bound in a currency the product does not price in means
nothing.

Constraints: each max ≥ its min; ages within 0–120; terms and sums positive.
Written as `NOT VALID`-free plain CHECKs since no existing row can violate them.

## 4. API shape, and a pre-existing bug fixed on the way

Six more parameters on a method that already takes nine is not an API. The
bounds travel as one `EligibilityBounds` record.

**While adding it, `publishVersion`'s existing convenience overload turned out to
carry the Build 1 bug.** It is a `default` method on `ProductApi` delegating to
the real one, so Spring's proxy passes it to the target and the delegating call
is a self-invocation that never re-enters the proxy — meaning the whole publish
runs **outside the `@Transactional` the implementation declares**. Roughly 48 of
its 67 callers use that overload.

The consequence here is not a lost event (this method publishes none) but a
partial write, and the window is real:

1. every currently-ACTIVE version is retired with `saveAndFlush` — deliberately
   flushed immediately, so it is committed the moment there is no surrounding
   transaction;
2. the new version is inserted;
3. rating factors, base rates, benefits and funds are inserted;
4. the product definition is activated.

Duplicate rating factors are rejected before step 1, so that particular failure
cannot strand anything. Concurrency can: two publishes for the same product both
retire the active version, then one loses on `ux_product_version_active`. With a
transaction the loser rolls back. Without one, its retirement is already
committed and **the product is left with no active version at all** — unsellable,
with no rollback and nothing logged.

I have not observed this in the wild; the mechanism is the one proven
empirically in Build 1 §9.1, and the window above is read off the code. The fix
is the same: declare every overload abstract on the interface and implement each
in `ProductApiImpl` carrying `@Transactional`, so delegation happens inside an
already-open transaction.

## 5. Screens

`CreateProductPage`'s version form grows an "Eligibility" group. All six
optional, with the hard/soft distinction stated in the caption so an actuary
authoring the product knows which bounds will refuse business and which will
merely flag it — that is the difference between a bound they set casually and one
they think about.

## 6. Done gate

Backend unit/integration plus the product contract test; `tsc`, `eslint`,
Vitest; and the full Playwright suite. `V6` needs a manual `psql` apply and a
backend restart before real-stack e2e reflects it.

A test that the convenience overload is transactional is part of this build, not
an optional extra — it is the only thing that would catch the bug in §4 coming
back.

---

## 7. Notes from building it

### 7.1 The regression guard was verified by reintroducing the bug

Writing a test that *claims* to guard something is not the same as guarding it,
so the guard was checked the only way that means anything: the `default` method
was temporarily restored, the test run, and it failed with the message naming
the cause —

```
publishVersion must not be a default method: Spring's proxy cannot apply
@Transactional to one, so its delegation runs untransacted
  ==> expected: <false> but was: <true>
```

— then the fix was restored. Any future assertion that this guard works should
be re-earned the same way.

### 7.2 The first version of that test was worthless

The first attempt was called `theConvenienceOverloadRunsInsideATransaction` and
its body published twice and counted versions. It would have passed with the bug
present. It was replaced before it was ever run in anger, and it is recorded here
because the failure mode is the one this codebase keeps meeting: a test whose
name asserts a property its body never checks.

### 7.3 Why the guard is structural, not behavioural

Two behavioural approaches were considered and both are dead ends:

- **Force the partial write.** Needs a concurrent publisher losing on
  `ux_product_version_active`, which is real but awkward to stage
  deterministically inside a Spring integration test.
- **Wrap the call in an outer transaction and roll it back.** Proves nothing:
  Spring Data's repository transactions would join that outer transaction and
  roll back whether or not `publishVersion` is annotated, so both the healthy and
  the broken code produce identical results.

The structural check — no overload is a `default` method, every implementation
carries `@Transactional` — asserts exactly the two properties that make the
failure impossible, and it is the thing a regression would break. Structural was
the honest option here rather than the lazy one.

### 7.4 The severities are not in the database, deliberately

Hard-versus-soft is a property of the *kind* of bound, not of any product. A
column would let two products disagree about whether an unpriceable age is
refusable, which is not a business decision anyone should be able to make by
filling in a form. The severities live in `EligibilityBounds`'s Javadoc and, from
Build 4, in the gate code.

### 7.5 The authoring form states the severity

`PublishVersionForm`'s Eligibility group says in its caption which bounds refuse
business and which only flag it. That is not decoration: an actuary setting a
maximum entry age is making a refusal, and one setting a maximum sum assured is
making a referral, and the form should not make those look like the same act.
