# Offer and acceptance: cover starts when the premium clears

**Status:** approved, ready for implementation
**Follows:** `2026-09-08-underwriting-decision-and-single-issuance.md`, `2026-09-08-proposal-capture-design.md`
**Project 1 of 2.** Project 2 is the communication module, which consumes the events this
produces. See "Why this is two projects" below.

## The problem

A policy goes in force the moment an underwriter accepts. The customer has agreed nothing and
paid nothing.

From that instant the platform carries the risk, bills them, accrues the agent's commission,
cedes to the reinsurer and reports new business to TIRA. If they never pay, every one of those
is wrong and has to be unwound. `PolicyStatus.PROPOSED` exists for exactly this stage and is
unreachable: `issuePolicy` calls `activate()` in the same transaction.

The researched flow (`docs/research/life-new-business-flow.md`) puts three stages between the
decision and cover: an offer, its acceptance, and the first premium. This closes all three at
once, because the business decision is that **paying is accepting**.

## Decisions taken

Each of these was a real fork, and the reasoning matters more than the choice:

1. **Payment is acceptance — one act, not two.** No separate "customer accepted" step. Handing
   over money is the acceptance, which is how most Tanzanian retail life business runs. The
   cost, accepted knowingly: an offer nobody responded to and an offer accepted but unpaid look
   identical. If loadings turn out to be common enough that the walked-away number matters,
   splitting acceptance out later is additive rather than a rework.
2. **Manual issue bypasses, but must say why.** It goes straight to ACTIVE and carries a named
   `issuanceBasis`. Data migration and backdated issuance are legitimate and would break under
   a universal rule — and a rule people cannot follow gets faked, which is worse than an honest
   bypass. This is what finally makes the bypass types gate a behaviour rather than label one.
3. **Only billing fires at offer time.** Commission, cession and the regulatory count are all
   statements about a contract actually on risk. They move to activation.
4. **Thirty days, from reference data.** Long enough for mobile money after payday and for an
   agent's field receipt to reconcile; short enough that nobody goes on risk against medical
   evidence assessed a season ago.

## The offer IS the proposed policy

No quoted premium on the underwriting case. An accepted decision creates the policy in
`PROPOSED`, and the premium on that record is the offer.

A separate quoted-premium field would give two numbers that can disagree, and the one the
customer pays is the one on the policy. This also settles where `NOT_TAKEN_UP` lives — and
reverses an earlier position in these documents, which said it belonged on the case. That was
right while the offer was an abstraction. It is not one now.

`NOT_TAKEN_UP` is a terminal `PolicyStatus`. The case stays `DECIDED` with outcome `ACCEPT`:
it *was* decided, and the customer simply did not take it up. Two different facts, both worth
keeping.

## The lifecycle

```
decide(ACCEPT | LOADED)
  → policy created PROPOSED, premium = the offer
  → PolicyIssued          "the contract record exists"
  → billing raises the first invoice                    (unchanged)

first billing.PremiumCollected for the policy
  → policy.activate()     no-op unless PROPOSED, so a later premium changes nothing
  → PolicyActivated       "on risk, premium received"

30 days unpaid (pg_cron sweep)
  → policy NOT_TAKEN_UP   terminal; case stays DECIDED / ACCEPT
```

### Manual issue: the basis decides, not the path

`issuanceBasis` becomes **required** on manual issue, and which value it carries decides
whether cover waits.

| Basis | Cover starts | Why |
|---|---|---|
| `MIGRATION` | immediately | The contract is already in force in the system being left. Its premiums have been paid, sometimes for years. |
| `CONVERSION` | immediately | Continuous cover from the converted policy; a gap would be a real lapse in someone's life assurance. |
| `REINSTATEMENT` | immediately | Reinstatement follows arrears being settled — the money has already arrived. |
| `UNDERWRITING_OVERRIDE` | waits for the premium | New business. A manual review overturning an automated block changes who may be covered, not whether they must pay. |
| `GUARANTEED_ISSUE` | waits for the premium | New business. Guaranteed acceptance waives evidence of insurability, not the premium. |

This is a refinement on the original decision, which was "manual issue always bypasses". That
would have made the exception path a way to skip the money rule for ordinary new business —
the exact shape of the problem this whole line of work started from. The three bases that
bypass are the three where cover genuinely already exists somewhere; the two that do not are
new business wearing an exception's clothes.

Where the basis bypasses, the policy is created ACTIVE and both events fire together, so every
downstream consumer behaves exactly as it does today.

## What changes, module by module

| Module | Change |
|---|---|
| `policy` | `issuePolicy` no longer activates unconditionally; `activateOnFirstPremium`; `NOT_TAKEN_UP`; `PolicyActivated` event |
| `billing` | Stays on `PolicyIssued`. Arrears sweep scoped to in-force policies — see below |
| `distribution` | Commission accrual moves from `PolicyIssued` to `PolicyActivated` |
| `reinsurance` | Cession moves to `PolicyActivated` |
| `regreporting` | New-business projection moves to `PolicyActivated` |
| `underwriting` | Unchanged. The decision already does its job |

`PolicyIssued` keeps its name deliberately. Renaming it would touch every consumer to say
something none of them needed to hear; what changed is its meaning, and the event catalogue
records that.

## Two consequences worth stating plainly

**Billing will open arrears cases against unpaid proposed policies.** `PolicyLapseRecommended`
is already guarded — the listener acts only on ACTIVE or SUSPENDED, so a proposed policy cannot
be lapsed by the arrears path, and the two sweeps cannot collide. But the collections queue
would fill with people who have not accepted yet, and somebody works that queue daily. The
arrears sweep is scoped to in-force policies as part of this change.

**No backfill.** Every existing ACTIVE policy stays ACTIVE, and cases already decided have
already issued. The rule applies from here. There is no source of truth for reconstructing
whether a historical policy's first premium ever cleared, and inventing one would be worse than
the gap.

## Why this is two projects

Notifying the customer belongs to the `communication` module, which has a schema
(`notification_template`, `notification_dispatch`, and a `processed_event` table for
idempotency) and **no Java code at all** — five `package-info.java` files. Adding notifications
here would mean designing a module's boundaries inside a lifecycle spec, and both would come
out compromised.

This project produces the events; they are durable in the audit journal whether or not anything
consumes them yet. Project 2 implements the module against events that already exist rather
than imagined ones.

The gap is real and is not pretended away: between the two projects, offers expire silently.
Nothing regresses — there are no offers at all today — but a customer whose offer lapses will
not hear about it until Project 2 lands.

## Testing

- An accepted decision leaves the policy `PROPOSED` and `isInForce()` false.
- The first `PremiumCollected` activates it; a second does not re-activate or re-fire
  `PolicyActivated`.
- Commission, cession and the regulatory projection are all absent on a `PROPOSED` policy and
  present after activation — one test each, since each is a different module's listener.
- A claim against a `PROPOSED` policy is refused. (`Policy` already refuses this, "PROPOSED
  above all"; the test pins it now that PROPOSED is reachable.)
- The sweep lapses at 30 days and not at 29, and reads its window from reference data.
- Manual issue is refused without an `issuanceBasis`. `MIGRATION` is ACTIVE immediately;
  `UNDERWRITING_OVERRIDE` is `PROPOSED` and waits for the premium like any other new business.
- The activation listener is idempotent: a second `PremiumCollected` on an already-ACTIVE
  policy neither re-activates it nor re-fires `PolicyActivated`, which would double-accrue
  commission and double-cede the risk.
- E2E: decide a case, see the offer, pay it, see cover start.

## Out of scope, carried forward

- **Customer notifications** — Project 2.
- **Per-product base rates in issuance.** Automatic issuance still prices from one global
  reference rate and ignores the product's own base rate table.
- **The case-to-policy back-reference**, and a case status meaning issued.
- **s.119 free-look dates** — proposal signed and policy delivered — and a free-look
  cancellation distinct from surrender.
- **Re-opening a declined case.** Still impossible; only POSTPONED can be reworked.
- **KYC gates nothing** in the backend; only the console's party picker filters to verified
  people.
