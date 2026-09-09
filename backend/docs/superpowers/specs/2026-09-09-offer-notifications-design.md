# Offer notifications — design

**Date:** 2026-09-09
**Project:** 2 of 2 (Project 1 was offer and acceptance)
**Status:** design, awaiting review

## The problem

Cover now waits for the first premium. A customer whose proposal is accepted has thirty days
to pay, and after that the offer closes as `NOT_TAKEN_UP` and they are not insured.

Nobody tells them any of this.

That is not a gap in polish, it is the gap that makes the thirty-day window unfair. A deadline
nobody is told about is not a deadline, it is a trap — and the sweep that enforces it went in
during Project 1 with no notification behind it. Around 15% of accepted proposals are never
taken up (SOA new-business data); some of that is genuine change of mind, and some of it is
people who did not know they still had to do something.

## What exists

The `communication` module is a schema and five `package-info.java` files. No Java at all.

- `communication.notification_template` — `template_key`, tenant, channel, language (`sw`/`en`),
  body, created_at.
- `communication.notification_dispatch` — one row per attempt: party, template, channel, status
  (`PENDING`/`SENT`/`FAILED`), `dispatched_at`.
- `communication.processed_event` — event-id dedup. The migration's own comment says
  communication is the module singled out as needing explicit dedup "since a resent SMS is a
  real user-visible duplicate, not a harmless no-op".

`allowedDependencies = { "party::api", "refdata::api" }`. That is sufficient:
`PartyApi.getPartyDetail(UUID)` returns `phoneNumber` and `email`. `PartyView` deliberately does
not, and must not be widened — its own javadoc explains that four modules read it as an existence
check.

31 of the 59 events in the catalogue name `communication` as a consumer. None has a listener.

## Scope

**The offer lifecycle only** — four notifications — plus the machinery they need, built once so
the other 27 events become listeners rather than a second foundation.

| Trigger | Meaning to the customer |
|---|---|
| `policy.PolicyIssued` where the policy is `PROPOSED` | Your cover is ready. Pay X by DATE to start it. |
| reminder sweep, a configurable number of days before expiry | Your offer closes on DATE. |
| `policy.PolicyActivated` | You are now covered. |
| offer expired (`NOT_TAKEN_UP`) | Your offer has closed. You are not covered. |

Explicitly **not** in scope: the other 27 events, inbound messages, delivery receipts, a
preference centre, and USSD/PUSH. Adding them is listener work on the same machinery.

## Channels

**SMS is the primary channel, with email in addition where an address is on file.** Most
customers here are reachable by phone; email is a minority and a bonus, not a substitute.

There is no SMS gateway on this platform. One will be mocked, following the precedent this repo
already set with `mock-mobile-money`: a WireMock container (`mock-sms-gateway`) with stub
mappings in a directory, so the gateway ACL is exercised for real without live aggregator
credentials. Email sends over SMTP to Mailpit, which is already in the compose file for exactly
this purpose.

**Both phone and email are optional on a party.** `ContactInfo` pattern-constrains the phone and
`@Email`s the address, but neither is required, so a party can have no reachable channel at all.
That case gets a `FAILED` dispatch row naming the reason — never a silent drop. An
unreachable customer is an operational fact somebody needs to see, not an absence of work.

## How it works

```
policy.PolicyIssued ─┐
policy.PolicyActivated ─┼─→ PolicyNotificationListener ─→ NotificationApi.notify(...)
policy.PolicyNotTakenUp ─┘                                        │
                                                                  ├→ processed_event  (dedup)
communication.sweep_offer_reminders()  ─→ (reminder) ─────────────┤
   pg_cron, daily                                                 ├→ resolve party contact
                                                                  ├→ render template (sw/en)
                                                                  └→ NotificationSender port
                                                                        ├─ SmsSender  → gateway
                                                                        └─ EmailSender → SMTP
                                                                     ↓
                                                          notification_dispatch row
```

**One new event is needed.** Nothing currently announces that an offer expired: the sweep does a
raw `UPDATE` to `NOT_TAKEN_UP` and publishes nothing, because in Project 1 there was no consumer.
`policy.PolicyNotTakenUp` has to exist for the fourth notification, and the sweep is SQL, so it
cannot publish a Spring event. The publication belongs on a policy-side path that observes the
transition rather than inside the sweep.

**Dedup is the load-bearing part.** Everywhere else on this platform, redelivery is absorbed
silently because the write is idempotent. Here it is not: a second SMS is a second SMS. Every
handler records `event_id` in `processed_event` inside the same transaction as the dispatch row,
and returns early if it is already present.

**The reminder is a pg_cron sweep**, matching `policy.sweep_expired_offers()`: a time-based
cross-tenant business action, never a Spring `@Scheduled`. It selects `PROPOSED` policies whose
offer closes within the reminder window and which have no reminder dispatch already.

**Templates are seeded reference data**, in Swahili and English, one row per
`(template_key, channel, language)`. Language per party is out of scope for this project;
Swahili is the customer-facing default, matching the schema's own comment.

**Failure is recorded, not retried, in this project.** A gateway timeout marks the dispatch
`FAILED` with the reason. Automatic retry needs a backoff policy and a dead-letter story that
deserve their own decision rather than being invented here — and a `FAILED` row is visible,
whereas a silent retry loop is not.

## What could go wrong

- **Double-sending on redelivery** — the whole reason `processed_event` exists. Mitigated by
  writing the dedup row in the same transaction as the dispatch.
- **A notification blocking issuance.** Listeners are `AFTER_COMMIT` with
  `PROPAGATION_REQUIRES_NEW`, so a failed send cannot roll back a policy. The premium was
  collected and the cover started; a failed SMS must not undo either.
- **Telling somebody they are covered when they are not.** The `PolicyActivated` message is the
  one that must never fire early. It is driven by the event, which fires only on the real
  `PROPOSED → ACTIVE` transition and exactly once.
- **A reminder after the offer already closed.** The sweep must re-read status at send time, not
  trust the row it selected.

## The console surface

Seeded templates with no screen behind them would mean a typo in a customer's SMS needs a
migration to fix, and no way for anyone to answer "was this customer actually told?" — which is
the first question the desk will ask when somebody rings up saying their policy lapsed without
warning. A notification system nobody can see is one nobody can trust.

**A `Communications` nav group in the staff console, with two screens.**

**Message templates** (`configuration`-grade, but grouped here for discoverability). Lists every
template by key, channel and language, shows the body, and allows the body text to be edited.

Deliberately *not* create or delete. A template key with no listener behind it is dead text, and
a listener whose key has been deleted fails every send — so the set of keys is defined by the
code that sends them, and the console edits wording rather than inventing slots. Edits are
audited like any other write on this platform, because changing what a customer is told is a
business act.

**Messages sent** — the outbox. Every `notification_dispatch` row: who, which template, which
channel, when, and its status. A `FAILED` row shows the reason it failed, which is the whole
point of recording failures rather than retrying silently.

**And a `Messages` panel on the policy detail page**, which is the one that earns its place
operationally. Looking at an offer, the question is not "what did we send today" but "has *this*
customer been told about *this* policy, and did it arrive?" Answering that from a global outbox
means knowing the party id and filtering by hand.

### What this adds to the backend

`openapi-communication.yaml`, and a controller with three reads and one write:

- `GET /notifications/templates` — staff.
- `PUT /notifications/templates/{templateKey}` — the body text only, admin-gated, audited.
- `GET /notifications/dispatches` — filterable by party, policy, status.
- The per-policy panel reads the same dispatches endpoint filtered by policy.

`notification_dispatch` currently has no `policy_number` column, so filtering by policy is not
possible today. It gains one, nullable: not every notification is about a policy, and the ones
that are should say so rather than forcing the reader to infer it from the template key and the
timestamp.

## Testing

- Testcontainers integration tests per listener, asserting a real `notification_dispatch` row
  and a real gateway call against WireMock.
- A redelivery test per handler: publish the same envelope twice, assert one dispatch row.
- A psql test for the reminder sweep loaded from the `_post-migration` file operations applies,
  not an inlined copy — the same discipline Project 1 used, and for the same reason.
- An unreachable-party test: no phone, no email, one `FAILED` row naming why.
- E2E: issue an offer, assert the message lands in Mailpit and the gateway stub was called.

## Open question for review

The reminder window (how many days before expiry) is a business parameter. Proposed as refdata
`TZ_OFFER_REMINDER_DAYS`, seeded 7 as a placeholder alongside `TZ_OFFER_VALIDITY_DAYS`'s 30 —
one reminder, a week out. Whether it should be one reminder or several is a business call, not a
technical one.
