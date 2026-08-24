# ADR-001: Unify Client and Agent Identity Under a "Party" Domain Concept

**Status:** Accepted
**Date:** 2026-08-05
**Deciders:** Lead Solution Architect
**Related deliverable:** Phase 0 / Deliverable 1 — Domain Map & Bounded Contexts

## Context

The platform's business capability list specifies "Client & Agent Registration (individuals, groups, corporates)" as a single capability. Clients, agents, brokers, and bancassurance partner staff all share the same underlying identity concerns: individual vs. corporate representation, contact details, KYC verification status, document attachments, and relationships to other parties (e.g., a corporate's authorized signatory, a group's members).

Modeling these as entirely separate entity hierarchies (`Client`, `Agent`, `Broker` as unrelated types) would duplicate identity/KYC logic across at least three places and complicate the common case of a person changing role over time (e.g., a long-standing client who becomes a licensed agent).

## Decision

Introduce a **`Party`** aggregate root as the shared identity abstraction (standard DDD "Party" pattern), owned by the **Party Management** bounded context. `Party` captures identity, type (`Individual` / `Corporate` / `Group`), contact information, and KYC status.

Role-specific business behavior is kept out of Party Management and lives in the bounded context that owns that behavior:
- Being a policyholder, insured, or beneficiary is modeled in **Policy Administration**, referencing `PartyId`.
- Being an agent or broker (licensing, hierarchy, commissions) is modeled in **Distribution & Channel Management**, referencing `PartyId`.
- Being a claimant is modeled in **Claims Management**, referencing `PartyId`.

No bounded context other than Party Management owns identity/KYC data directly; all reference it by `PartyId` through Party Management's published API.

## UX Constraint (explicit, non-negotiable per this decision)

**This is a backend domain-modeling abstraction only. It must not leak into user-facing language.** Every channel (Web Portal, Mobile App, Agent Portal, Staff Back-Office, USSD, SMS) continues to say "Client," "Customer," "Agent," or "Broker" as appropriate to that screen/flow. No UI copy, notification template, or TIRA-facing report should ever display the word "Party." Front-end and BFF/Omnichannel Access layers are responsible for this translation; it must be called out explicitly in any UI copy guidelines and API response DTOs consumed by front ends (response fields should be named per the business role — e.g., `agentName`, not `partyName` — even where the underlying join key is `partyId`).

## Consequences

**Positive:**
- Single source of truth for identity and KYC across the platform — no duplicate customer/agent records, no reconciliation problem when a person holds two roles.
- Clean support for a client becoming an agent, or a corporate group both purchasing a group policy and later becoming a bancassurance-adjacent referral partner, without a data migration.
- Distribution & Channel Management, Policy Administration, and Claims Management stay focused on their own invariants rather than re-implementing KYC logic.

**Negative / risks to manage:**
- Requires discipline at every module boundary and every API/DTO to translate `Party`/`PartyId` into role-specific, business-facing names. This is an ongoing code-review concern, not a one-time task — flag in Deliverable 2's API design and in any future front-end contribution guidelines.
- Party Management becomes a hard dependency for nearly every other module; its API must be designed for stability (Open Host Service / Published Language) since a breaking change there ripples widely.

## Alternatives Considered

1. **Separate `Client` and `Agent` entities with no shared identity model.** Rejected: duplicates KYC/identity logic, breaks down when one person holds both roles.
2. **Single `User` table conflating authentication identity with business identity.** Rejected: conflates Identity & Access Management (Keycloak-backed authentication) with Party Management (business identity); a person can exist as a Party (e.g., a minor beneficiary) without ever having platform login credentials, and vice versa is a bad coupling to introduce.
