# Life New Business: Who Issues a Policy, and How Underwriting Relates to Issuance

**Primary-source research note — Digital Life Insurance Core Platform (Tanzania)**
Compiled 2026-09-08.

> **Method note.** Every substantive claim below is followed inline by the source that owns it. Sources are graded in the source list at the end: **[P]** primary (the standards body, regulator, vendor or statute that authored the material), **[P-mirror]** a verbatim third-party republication of a primary artefact, **[S]** secondary. Where two sources disagree, both positions are given rather than reconciled.
>
> **What I could not reach, stated plainly:**
> - ACORD's actual transaction specifications and implementation guides (the *103 New Business for Life* implementation guide, the *1125 Pending Case Status* spec) are **members-only** on acord.org and were not read. What *was* read is ACORD's own public Fact Sheet PDFs, which are first-party ACORD documents and are quoted verbatim below. Nothing here is sourced from someone else's description of the ACORD spec.
> - The **OLifE typecode enumerations** (`OLI_LU_POLSTAT`, `OLI_LU_POLISSUE`, `OLI_LU_APPTYPE`) and the **OLifE object definitions** (`Policy`, `Holding`, `ApplicationInfo`) were read from the PilotFish ACORD Model Viewer, a public republication of ACORD's model with ACORD's own definition text. It is a faithful mirror, not the standard itself. Graded **[P-mirror]** throughout, and flagged wherever load-bearing.
> - **Reinsurer underwriting manuals are gated.** Swiss Re *Life Guide* is registered-user-only; rgare.com was unreachable from this environment. No claim below rests on a reinsurer manual. The SOA/Milliman survey fills that gap with real industry data.
> - **Oracle OIPA documentation was unreachable** (docs.oracle.com DNS failures, repeatedly). No OIPA claim appears below. Socotra is the vendor source used, because its developer documentation is fully public and unusually explicit about the exact transition this note is about.
> - **TIRA:** the Insurance Act and the Insurance Regulations were reached and are quoted. No TIRA *circular or guideline* specifically on new-business processing or policy-issuance turnaround times was found. That does not mean none exists — it means it was not reachable from the public TIRA document library in this session.

---

## Summary: the canonical flow

The industry does not have one flow, but it has one **shape**, and two standards bodies plus one vendor describe it almost identically. ACORD's own words:

> "The New Business Process includes all of the steps necessary to facilitate the completion of an application and issue a policy/contract. It begins with product rules and guidelines, continues with gathering the information necessary for a carrier to make a decision to accept the application and issue a policy/contract, communicating that decision, and delivering the policy/contract."
> — ACORD, *Life & Annuity Program: Industry New Business Process* Fact Sheet v02 **[P]**

Note the four verbs in that sentence, in order: **gather → decide → communicate → deliver**. "Issue" is bundled with "accept" as one carrier act, and **delivery is a separate, later stage** — which matters enormously in Tanzania, because TIRA's cancellation clock partly runs from delivery.

| # | Stage | Artefact that exists | Its state | Who acts |
|---|---|---|---|---|
| 0 | Illustration / quote | Quote or illustration | ACORD `Quoted` (57); Socotra `draft` → `priced` | Agent / self-service |
| 1 | Informal / trial submission (optional) | Non-binding proposal | ACORD `FormalAppInd = FALSE` | Agent → carrier / reinsurer |
| 2 | Formal application signed | **Proposal form**, signed | ACORD `Applied For` (21) — "transmission of a formal app is successful" | Applicant + agent |
| 3 | Submitted to carrier | Application received at home office | ACORD `Pending Transmission` (22), then `Pending` (8); carrier assigns `HOAssignedAppNumber` | New business intake |
| 4 | Requirements ordering | Medical/paramedical/APS/labs outstanding | ACORD `Incomplete` (23); ACORD 121/1122 messages | New business + vendors |
| 5 | Underwriting assessment | Underwriting case | ACORD `Submitted to Underwriting` (54) | Underwriter, or an automated engine |
| 6 | Underwriting decision | Decision recorded on the case | `Approved` (24), `Conditional Approval` (53), `Counter offer` (26), `Approved Tentative Offer` (58), `Postponed` (62), `Carrier decline to issue` (27) | Underwriter / rules engine |
| 7 | Offer accepted, requirements settled | Amendment/counter-offer accepted, first premium paid | `Approve - Issue Hold` (103) if held; `Eligible, Issue Pending` (34) | Applicant + new business |
| 8 | **Issue** | **Policy contract, policy number assigned** | `Issued` (25), `Issued with Requirements` (44), `Policy was issued with changes` (43) | New business / policy issue function (or STP) |
| 9 | Delivery | Policy document delivered, delivery receipt | `PolicyDeliveredInd`, `PolicyDeliveryReceiptDate` on ApplicationInfo | Agent / post / e-delivery |
| 10 | Placement (in force) | Contract on risk | `Active (inforce)` (1) | — |
| 10a | Failure to place | — | `Not Taken` (7) / `Not Taken Out` (65) / `Policy was not placed` (51) | Applicant walks away |
| 11 | Free look / cooling off | Cancellation window open | `Canceled - Free Look` (50) | Policyholder |

Status codes are `OLI_LU_POLSTAT` values from ACORD's OLifE model **[P-mirror]**.

**The one-line answer to the headline question:** in the ACORD model there is no separate "application record" — the application is a *phase in the life of the policy object*, and the policy record therefore exists from before submission. Socotra takes the opposite structural choice (a distinct quote entity) but reaches the same guarantee by making the issued policy carry **the quote's own identifier**. Both designs converge on: **one submission, one identity, one contract**.

---

## 1. The canonical new business flow, and when a POLICY comes into existence

### 1.1 ACORD: the policy object is the container; the application is a child of it

ACORD's OLifE object model nests as `Holding → Policy → ApplicationInfo`. The `ApplicationInfo` object's own definition names `Policy` as its **parent** and says:

> "Information related to the application for new insurance (all lines of business: annuities, life, long term care, etc...) as well the application data gathering and submission process. This is used to support the workflow associated with the submission process, providing information essential to determining where an application is in the submission process... **All data elements are 'point in time' and subject to subsequent change. However, once the policy is issued, information on ApplicationInfo is not expected to change.**"
> — OLifE `ApplicationInfo` **[P-mirror]**

So ACORD models an application as *metadata attached to the prospective policy*, frozen at issue. The prospective-policy states are real, ordered values of `Policy/PolicyStatus`:

- `Candidate` (116) — "Candidate Policy — **Not yet proposed**."
- `Quoted` (57)
- `Proposed` (12)
- `Applied For` (21) — "Transmission of a formal app is successful. This is only used for a formal application and only represents status from the perspective of the sender; i.e., the transmission of an application from a broker/vendor to the carrier. **It does not reflect a carrier status.**"
- `Pending Transmission` (22), `Pending` (8), `Incomplete` (23)
- `Submitted to Underwriting` (54)
- `Issued` (25)
— all **[P-mirror]**

The `Applied For` note is worth pausing on: ACORD explicitly distinguishes *the distributor's view* of a submission from *the carrier's view*. Two parties can hold the same logical case at different statuses, legitimately. Any system that models "application state" as a single global value is flattening something the standard treats as genuinely two-sided.

**Caveat I will not paper over:** ACORD is a *messaging* standard. `Policy` here is the shape of a message payload, not a mandate about how a carrier stores rows internally. It is strong evidence about how the industry *thinks* about the entity, and weaker evidence about physical schema.

### 1.2 Socotra: a distinct quote entity, an explicit state machine, and identity carried across

Socotra's public documentation gives a fully enumerated state machine **[P]**:

| State | Socotra's own definition |
|---|---|
| `draft` | "The quote is mutable, so its data, coverage terms, and other attributes can be changed." |
| `validated` | "The quote has been validated, both against its configuration and any custom validation." |
| `priced` | "The quote has pricing generated." |
| `underwritten` | "The quote has passed underwriting checks." |
| `accepted` | "The quote has been accepted but not yet issued. **This state requires successfully passing underwriting checks.**" |
| `issued` | "The quote has been issued, and a policy has been created. **The resulting policy has the same locator as the quote.**" |
| `underwritingBlocked` | Cannot proceed due to underwriting flags, but not declined or rejected |
| `declined` | "The quote did not pass underwriting but **it can be re-underwritten or reset**." |
| `rejected` | "The quote did not pass underwriting and **cannot be re-underwritten or reset**." |
| `refused` | Customer rejected coverage |
| `discarded` | "The quote has been disposed of so it cannot be processed further." |

The API sequence is one identifier threaded through six calls — Create Quote (returns `quoteLocator`) → Validate → Price → Underwrite → Accept → Issue, each taking the same `quoteLocator` **[P]**. The policy comes into existence **only at step 6**, and inherits the quote's locator.

So: two structurally different answers to "when does a policy record exist" — ACORD says *from the quote*, Socotra says *at issue* — but they agree that **there is exactly one identity from quote to contract**.

### 1.3 What "issued" does not mean

ACORD keeps three things apart that are easy to collapse:

- **Issued** (25) — the contract exists.
- **Delivered** — `PolicyDeliveredInd`, `ActualPolicyDeliveryMethod`, `PolicyDeliveryReceiptDate`, `HOPolicyMailDate` are all separate ApplicationInfo fields **[P-mirror]**.
- **Placed / in force** — `Active (inforce)` (1), with the anti-status `Policy was not placed` (51) and `Not Taken` (7).

A policy can be issued and never placed. The SOA/Milliman survey measures exactly this: "A policy was issued on average almost 75% of the time and the policy was not taken or withdrawn on average over 15% of the time" **[P]**. Roughly one issued-or-decided case in six never becomes a live contract. A model with no NTU state cannot represent 15% of real outcomes.

---

## 2. Who issues

### 2.1 The occupational split: underwriters decide, policy processing records

The US Bureau of Labor Statistics defines insurance underwriters as those who "evaluate insurance applications and decide whether to approve them", with duties including "Decide whether to offer insurance" and "Determine appropriate premiums and amounts of coverage" **[P]**. Nothing in the BLS duty list mentions producing or issuing the contract document.

O*NET's separate occupation, *Insurance Claims and Policy Processing Clerks* (43-9041.00), is defined as "Process new insurance policies, modifications to existing policies, and claims forms", with tasks including "Process and record new insurance policies and claims" **[P]**.

These are two distinct occupations in the US federal occupational taxonomy. That is the cleanest primary-source evidence of the division: **the underwriter reaches a decision; a separate new business / policy-issue function turns an accepted decision into a contract record.** Worth noting honestly: O*NET does **not** contain a task literally titled "issue policies", so this is an inference from the two definitions read together, not a quoted assertion.

### 2.2 ACORD puts the underwriter on the application, not on the issuance

ACORD's `ApplicationInfo` carries `HOUnderwriterName` ("Home Office Underwriter Name"), `LastUnderwritingActivityDate` and `LastUnderwritingActivityTime` **[P-mirror]** — the underwriter is recorded as an actor on the *case*. Meanwhile `HOCompletionDate`, `HOPolicyMailDate`, `HOPolicyRemailDate` and `RequestedIssueDate` describe home-office issuance and despatch as a separate track.

The status `Approve - Issue Hold` (103) is the decisive one:

> "To have the case status 'Case is approved but issue process is on hold'. **Case will not be issued until this hold is removed**." **[P-mirror]**

That status can only exist in a world where *approval* and *issuance* are distinct acts by distinct actors with distinct gates. If the underwriter's approval issued the policy, "approved but on hold" would be unrepresentable.

Similarly `Eligible, Issue Pending` (34) and `Issued with Requirements` (44) both describe the gap between "underwriting is done" and "the contract is out the door" **[P-mirror]**.

### 2.3 Where the sources disagree

They do disagree, and the disagreement is real rather than terminological.

- **Position A — the underwriter decides and hands off.** BLS/O*NET occupational split **[P]**; ACORD's `Approve - Issue Hold` **[P-mirror]**. Under this reading, "issue" is a back-office administrative act, and the underwriter's authority boundary is *accept / rate / postpone / decline / counter-offer* and nothing further.
- **Position B — underwriting gates the transition and there is no separate issuer.** Socotra's model has no issuance role at all: `accepted` "requires successfully passing underwriting checks", and the underwriting plugin fires at three points — "when underwriting a quote", "when accepting a quote", and **"when issuing a quote"** **[P]**. Underwriting in Socotra is a *guard on the issue call*, not a person who hands over to another person.

Both are correct about different operating models. Position A describes a traditional home office with a distinct new-business department. Position B describes a digital-first carrier where issuance is a system function that underwriting rules gate. The design consequence is the same either way: **an accepted decision is a precondition of issuance, not an act of issuance.**

Note also that in ACORD the *agent* never issues. The agent's role is bounded to: signing and submitting the application (`AppWritingAgentSignatureOK`, `SubmissionDate`), holding cash with application (`AgentCWADate`, `CWAAmt`), and delivering the policy and returning the delivery receipt **[P-mirror]**. The single exception is `Field Issue` (`OLI_COVISSU_FIELDISSUE`, 4) as a policy-issue type **[P-mirror]** — a real but narrow, product-gated pattern.

### 2.4 STP / auto-issue: how much is really automatic

The SOA Research Institute's *2022 Accelerated Underwriting Practices Survey* (Milliman, published November 2023) is the best public data **[P]**. It classifies every AU-eligible application on two axes:

Underwriting **outcomes**:
1. Neither fluids nor an underwriter was needed
2. Fluids were not required, but an underwriter was needed
3. Both fluids and an underwriter were needed

Underwriting **decisions**: Issued / Not taken–Withdrawn / Declined–Postponed.

Findings worth carrying:

- "It should be noted that **some companies do not allow Outcome (1)**" — i.e. some carriers require a human underwriter to touch every case regardless. One respondent comment states flatly: "All cases are reviewed by an underwriter (light-touch for AUW)."
- True no-human, no-fluids straight-through issuance averaged **14%** of AU-eligible cases in 2021; the largest single bucket was "both fluids and an underwriter were needed" at 32% issued.
- The report is explicit that STP has no agreed definition: "the industry lacks a standard definition for straight-through processing rate."

The lesson for a design: **auto-issue is a path, never the only path.** Every carrier surveyed retains a referral route to a human underwriter, and a meaningful share disable full STP entirely. A system that can only issue automatically is as wrong as one that can only issue manually.

---

## 3. When underwriting is skipped entirely

ACORD enumerates the legitimate bypasses as a first-class typecode, `OLI_LU_POLISSUE` ("Policy Issue Type") **[P-mirror]**:

| Code | ACORD's definition |
|---|---|
| `Full Underwriting` (1) | "Answering all of the medical questions in the policy." |
| `Simplified Underwriting` (2) | "Answering slightly different and less medical type questions as compared to Full Underwriting." |
| `Guaranteed Issue` (3) | "**The right to purchase insurance without being required to provide evidence of insurability.**" |
| `Field Issue` (4) | — |
| `Financial Underwriting` (5) | — |
| `Mass Underwriting` (6) | — |
| `Reduced Underwriting` (7) | — |
| `Blended` (8) | "Policy has Underwritten and Guaranteed Issue coverages. Only applies at Policy Level" |
| `Conversion - No underwriting done` (9) | — |
| `Aviation` (10) | Special underwriting rules |
| `Conversion - Non-contractual` (11) | "Non-contractual conversion — **Underwriting is needed**" |
| `Express Underwriting` (12) | "a data-driven underwriting process used to render a decision without the need for invasive requirements such as a physical examination or an Attending Physician Statement (APS)." |

And the evidence sub-type, `OLI_LU_POLISSUESUB` **[P-mirror]**: `Non-Medical` (1) — "Policies require a more extensive health history than simplified issue policies. **No medical or paramedical exam is required.**"; `Paramedical` (2); `Full Medical` (3).

Three structural points fall straight out of this list:

1. **The bypass is a property of the contract, permanently recorded.** ACORD does not model "we skipped underwriting" as an absent case. It models it as a *typed attribute of the issued policy*, which survives into inforce administration and is available at claim time. Guaranteed issue is a fact about the policy, not a hole in the audit trail.
2. **`Blended` proves the granularity is per-coverage.** One contract can carry a fully underwritten base benefit and a guaranteed-issue rider.
3. **Conversions split on whether underwriting is genuinely absent.** `Conversion - No underwriting done` (9) versus `Conversion - Non-contractual` (11) — "Underwriting is needed". The word "conversion" alone does not authorise a bypass; the contractual right does.

### 3.1 Group life

NAIC Model 565, the *Group Life Insurance Definition and Standard Provisions Model Act* **[P]**, is explicit that evidence of insurability in group business is a **policy-level configuration**, not a per-member decision:

> "§D. The policy shall contain a provision setting forth **the conditions, if any, under which the insurer reserves the right to require a person eligible for insurance to furnish evidence of individual insurability** satisfactory to the insurer as a condition to part or all of his coverage."

The same Act gives the master-policy/certificate structure (§G: the insurer issues to the policyholder, for delivery to each insured person, a certificate) and a two-year incontestability window from date of issue (§B).

It also contains a genuine, statutory, no-underwriting issuance right — the conversion privilege (§H): on termination of employment, the person "shall be entitled to have issued to him or her by the insurer, **without evidence of insurability**, an individual policy of life insurance... provided application for the individual policy shall be made, and the first premium paid to the insurer, **within thirty-one (31) days after termination**".

That is the canonical shape of a controlled bypass: a *named right*, a *time limit*, and a *premium condition*.

I did **not** find "actively at work" defined in any primary regulatory source reachable here. It appears in every practitioner description of group life, but I could not follow it to a statute, a model act or a standards typecode. Treat it as an unverified industry convention for now, or source it from a filed group policy wording.

### 3.2 The controls that stop the bypass being abused

This is where the primary sources are unusually good, because there is real enforcement history.

**Post-issue audits and random holdouts** (SOA/Milliman 2022 **[P]**):
- 63% of companies conduct post-issue audits; the primary reason is "to determine cases with misrepresentation or fraud", and the primary tool is an APS (80% of respondents).
- Post-issue audits covered on average **6%** of AU-eligible policies in 2022 (5% in 2019).
- **Random holdouts** — cases routed to full underwriting purely to measure what the accelerated path is missing — ran at **4%** in 2022, down from 8% in 2019.

The distinction matters: a *post-issue audit* is targeted and detective; a *random holdout* is untargeted and measures programme-level slippage. A platform that supports accelerated or simplified paths needs a mechanism for both, and they are different mechanisms.

**Incontestability and rescission** are the legal backstop: NAIC 565 §B for group **[P]**; NY Insurance Law §3203 requires individual policies to be "incontestable after being in force during the life of the insured for a period of two years from its date of issue" **[P]**. `Rescinded` (111) is a real ACORD policy status **[P-mirror]**.

**Group EOI has a documented, enforced abuse pattern.** The US Department of Labor's EBSA reached settlements with multiple group life insurers over evidence-of-insurability administration: insurers collected premiums for coverage for which EOI was never completed, then denied the death claim on EOI grounds. The settlements require that the insurer may not deny a claim for lack of EOI once premiums have been received for three months or more, and give the insurer 90 days from first premium to determine whether EOI is satisfied **[P — DOL/EBSA news releases; the release pages returned 403 to direct fetch and the underlying settlement PDF is a scanned image, so the terms here come from DOL's own release text as surfaced in search, and should be verified against the release before being relied on]**.

The design lesson is sharp and directly applicable: **an "underwriting not required" flag must be a decision someone made, at a recorded time, with a recorded basis — not the absence of a record.** If a member is on cover and billing but no one ever decided they were eligible, the insurer has already lost the argument.

### 3.3 Back-office direct issuance: migration, conversion, reinstatement

ACORD models these as **application types** (`OLI_LU_APPTYPE`) rather than as an absence of an application **[P-mirror]**:

`New Application` (1), `Reinstatement` (2), `Reissue` (3), `Conversion to New Policy Number` (4), `Conversion using old Policy Number` (5), `Block Conversion` (6), `Exchange` (9), `Term Conversion` (25), `Child Conversion` (18), `Family Conversion` (19), `For quote purposes only` (8), `To make a change in the contract` (7).

And on the status side, `Block Conversion` (63) **[P-mirror]**:

> "Used when the issue system is used as a **conversion vehicle for moving multiple policies from another administration system**. This status is intended for use on the new policy/system not the original."

That is data migration, given its own policy status. ACORD's position is unambiguous: **bulk-loading a book of business is a distinct, labelled, auditable mode of issuance — not an ordinary issue with the underwriting step quietly missing.** The same principle covers `Conversion with the same number` (64), which additionally requires that "The inforce policy with the same number must be terminated and carry exit code conversion out" — the identity is transferred, not duplicated.

`Reinstatement` gets both a status pair (`Pending Reinstatement` 38, `Reinstated` 37) and its own decline and failure states (`Carrier decline to reinstate` 28, `FIU Reinstatement Rejection` 115, `Canceled - Customer canceled reinstatement request` 114) **[P-mirror]** — i.e. reinstatement is itself an underwritten decision with its own outcomes, not a status flip.

---

## 4. The one-application-one-policy rule

### 4.1 ACORD: identity is continuous, and the pre-issue key is explicit

The mechanism is stated directly in the definition of `TrackingID` **[P-mirror]**:

> "This is the **Case/Tracking Number used to identify the application to reference the Holding until a policy number is assigned. Once a policy number is assigned, the reference to the Holding should be identified using the policy number.** In some cases this may be the same as the HOAssignedAppNumber, or it may be an agency generated ID."

And `HOAssignedAppNumber` **[P-mirror]**:

> "**Unique ID of application assigned by the carrier's home office used for tracking purposes of a holding until a policy number is assigned.** The tracking ID used by the carrier's home office to identify an application until it is assigned a policy number."

Read those together and the rule is structural, not procedural:

- There is **one `Holding`** throughout.
- Before issue it is addressed by an application number (carrier's `HOAssignedAppNumber`, and/or distributor's `TrackingID`).
- At issue it is addressed by `PolNumber`.
- **The policy number replaces the application number as the handle on the same object. It does not create a second object.**

The policy number itself is a carrier-controlled resource: ACORD defines a dedicated message, **106 New Business Policy Number Submission — "Used to enable a policy number to be requested from the carrier"** **[P]**. Policy numbers are *requested*, from a single authority, per case. That is a serialisation point by design.

`ApplicationType` on `ApplicationInfo` carries the `OLI_LU_APPTYPE` value **[P-mirror]**, so "is this a new application or a conversion of an existing contract" is answered on the record itself.

### 4.2 Socotra: identity reuse as the enforcement mechanism

> "When a quote is issued, resulting in the creation of a policy, **the policy has the same locator as the quote**." **[P]**

This is the strongest single design statement found in this research. The quote's locator *is* the policy's locator. Two policies from one quote is not prevented by a check — it is **unrepresentable**, because the second one would need the same primary identifier. Socotra reinforces it on the state machine side: `issued` is terminal ("Quotes cannot be issued twice — once `issued`, they cannot be reset"), whereas `declined` explicitly can be re-underwritten and `refused` can be reset **[P]**.

The general pattern, across both sources: **the guarantee lives in the identity, not in a duplicate-detection routine.**

I looked for an explicit idempotency-key mechanism in Socotra's public API documentation and did not find one. That is likely because their design does not need one for this case — the state machine refuses a second issue — but I am recording the absence rather than asserting they have it.

### 4.3 Duplicate submission

The vocabulary for a case that dies before becoming a contract is rich and precise, and every one of these is a *terminal state on the single record*, not a deletion **[P-mirror]**:

`Canceled - Customer canceled new business app` (39) — "Customer is withdrawing new business application"; `HO Withdrew` (59); `Producer Withdrew` (60); `Incomplete` (23); `FIU New Business Rejection` (98) — "A case may be rejected for FIU (Further Information Unobtainable) due to insufficient money or outstanding pre issue forms. A case pending in new business has a grace period (such as 45 days) for money and the pre issue forms. If these details are unobtainable for any reason, **the underwriter FIU rejects the case**"; `Not Taken` (7); `Not Taken Out` (65); `Policy was not placed` (51).

So a real system's answer to "what happens on a resubmission" is: the original case is still there, in a named terminal state, and it is discoverable. ACORD even provides `ResubmitDate` and `CaseRewriteDate` on `ApplicationInfo` **[P-mirror]** — resubmission is an event on the existing case.

### 4.4 The legitimate exceptions — one person, several policies

This is genuinely common and the standard has purpose-built flags for it. `ApplicationInfo` carries two booleans **[P-mirror]**:

- **`AdditionalInd`** — "TRUE indicates the contract is to be considered, **for underwriting purposes, as an additional contract to the related contract**."
- **`AlternateInd`** — "TRUE indicates the contract is to be considered, **for underwriting purposes, as an alternate contract to the related contract**."

That is precisely the distinction the question asks about, and note *how* it is drawn — **for underwriting purposes**, i.e. by aggregate risk exposure:

- **Additional** — the applicant genuinely wants both. Sums assured aggregate; retention and reinsurance limits apply to the total; `MaxRiskAmt` on `ApplicationInfo` is the field for the ceiling.
- **Alternate** — the applicant will take *one* of these. They must not aggregate, and only one will be placed; the other becomes Not Taken.
- **Accidental duplicate** — neither flag set, same application identity. This is the error case.

Other structurally legitimate multiples: `FormalAppInd = FALSE` (informal/trial submissions, explicitly "intended to result in a nonbinding proposal for the purpose of ratings and premiums") **[P-mirror]**; `ApplicationType = For quote purposes only` (8) — "The Quote process generally involves the submission of minimal information... The resulting quote is subject to formal review" **[P-mirror]**; group `CertificateNo` ("Certificate Number for Group Policies") on the `Policy` object, meaning group members hold certificates under one master contract rather than policies of their own **[P-mirror]**, consistent with NAIC 565 §G **[P]**.

**The discriminator, stated generally:** two contracts are legitimately distinct when they trace to *two distinct application identities* (two `HOAssignedAppNumber`s), or to one application explicitly flagged `AdditionalInd`. They are an accidental duplicate when they trace to *the same* application identity with no such flag. This is why the application number has to be a real key and not a display field.

---

## 5. Modelling vocabulary

### 5.1 The words

| Term | What it actually denotes | Source |
|---|---|---|
| **Illustration / quote** | Indicative pricing, no commitment. ACORD: `Quoted` (57); `IllustrationRunDate`, `IllustrationExpirationDate`, `IllustrationConfirmationNum` on ApplicationInfo | **[P-mirror]** |
| **Informal / trial application** | `FormalAppInd = FALSE`: "The trial or informal process is intended to result in a nonbinding proposal for the purpose of ratings and premiums" | **[P-mirror]** |
| **Proposal / proposal form** | Commonwealth and Tanzanian usage for the signed application. Tanzania's Insurance Regulations Part C legislates its contents directly | **[P]** |
| **Application** | North American usage for the same artefact. ACORD standardises the form set: 701 Part 1, 702 Part 2, 703 Part 3, 704 Supplemental Information, 765 Agent's Report, 782 Paramedical Examiner's Report | **[P]** |
| **Case** | The unit of work in new business/underwriting. ACORD uses it as a *qualifier on the application*, not a separate object: `CaseOrgCode`, `CaseLocationDate`, `CaseRewriteDate`, `CasePartyID` on ApplicationInfo; and in status text — "Case will not be issued until this hold is removed" | **[P-mirror]** |
| **Policy / contract** | The issued contract. `PolNumber`, `IssueDate`, `PolicyStatus` | **[P-mirror]** |
| **Certificate** | A group member's evidence of cover under the master policy. `CertificateNo`; NAIC 565 §G | **[P-mirror]**, **[P]** |
| **NTU / not-taken-up** | Issued but never placed. ACORD gives it three distinct codes: `Not Taken` (7), `Not Taken Out` (65), `Policy was not placed` (51) | **[P-mirror]** |
| **Free look / cooling off** | ACORD's own note on `Canceled - Free Look` (50): "In the United Kingdom and Europe, 'Canceled - Free Look' is called 'Cooling Off Cancellation' as the 'Free Look Period' is called the 'Cooling Off Period'." | **[P-mirror]** |
| **Counter-offer** | `Counter offer - made by HO` (26): "Issued other than applied for. Must include at least one material change(s), may additionally include nonmaterial change(s)". `CounterOfferOK` on ApplicationInfo records whether the applicant pre-authorised one | **[P-mirror]** |
| **Postponed** | `Postponed` (62) — decline-for-now, distinct from `Carrier decline to issue` (27) and `Risk Not Acceptable` (110) | **[P-mirror]** |
| **Rated / loaded** | ACORD has no single "rated" status; a rating is expressed through the offer, and `Approved Tentative Offer` (58) / `Conditional Approval` (53) carry the not-yet-final case | **[P-mirror]** |

Two vocabulary observations that bear on modelling. First, ACORD does **not** have a `Case` object — "case" is an adjective applied to the application. Second, `Proposed` (12) and "proposal form" are false friends: in ACORD `Proposed` is a pre-application status of the policy, whereas in Tanzanian/Commonwealth usage the proposal form *is* the signed application (ACORD's `Applied For`).

### 5.2 The authoritative status lifecycle

`OLI_LU_POLSTAT` **[P-mirror]** is the most authoritative enumerated life-policy status list I could reach. It has ~90 values across the whole contract lifecycle. The new-business-relevant subset, in flow order:

```
Candidate (116)              "Candidate Policy - Not yet proposed"
  -> Quoted (57)
  -> Proposed (12)
  -> Illustration declined (33)
  -> Applied For (21)        formal app transmitted (sender's view, not the carrier's)
  -> Pending Transmission (22)
  -> Pending (8)
  -> Incomplete (23)         "application was incomplete"
  -> Submitted to Underwriting (54)
      |-- Approved (24)                       "Approved for issue (may or may not have
      |                                        outstanding requirements)"
      |-- Conditional Approval (53)
      |-- Approved Tentative Offer (58)       "as opposed to final offer"
      |-- Counter offer - made by HO (26)     "Issued other than applied for"
      |-- Reconsider and Approve (97)
      |-- Postponed (62)
      |-- Carrier deferred (29)
      |-- Declined Not Eligible (35)
      |-- Risk Not Acceptable (110)
      |-- Carrier decline to issue (27)
      \-- FIU New Business Rejection (98)
  -> Approve - Issue Hold (103)   "Case is approved but issue process is on hold"
  -> Eligible, Issue Pending (34)
  -> Issued (25)
     Issued with Requirements (44)
     Policy was issued with changes (43)
  -> Active - Preliminary Term (48)
  -> Active (inforce) (1)
     "at least one, but possibly more, coverages or components of this contract are active"

Terminal / off-ramps at any point:
  Canceled - Customer canceled new business app (39)
  HO Withdrew (59) | Producer Withdrew (60)
  Not Taken (7) | Not Taken Out (65) | Policy was not placed (51)
  Canceled - Free Look (50)
  Rescinded (111)

Inforce onward:
  Grace Period (42) -> Lapse Pending (5) -> Lapsed (4)
  -> Pending Reinstatement (38) -> Reinstated (37) | Carrier decline to reinstate (28)
  Paid-Up (3) | Reduced Paid Up (20) | Extended Term (18) | Fully Paid-Up (47)
  Matured (40) | Surrendered (6) | Expired (17) | Terminated (14)
  Death Claim Pending (10) -> Death Claim Paid (11)
```

Two definitions in that list are worth internalising. `Active (inforce)` (1) is defined compositionally — "A Policy Status of 'Active' only indicates that **at least one, but possibly more, coverages or components of this contract are active**. Each individual coverage must be evaluated to determine the status of each specific component. **If any coverage is active, the contract is active.**" Policy status is *derived from* coverage status, not stored independently of it. And `Active - Preliminary Term` (48) captures the real gap where temporary cover is paid for but the basic policy's effective date has not arrived.

An open-source counterpoint, for contrast rather than authority: **openIMIS** (health micro-insurance, deployed in Tanzania among other countries) uses a five-value policy status — idle, ready, active, suspended, expired **[P]**. It is a much smaller lifecycle, in a domain with no individual medical underwriting, and it is *policy-only* — there is no application or case entity at all. Useful mainly as evidence that lifecycle richness tracks underwriting complexity.

### 5.3 Free look: three jurisdictions, three answers

| Jurisdiction | Period | Clock starts | Source |
|---|---|---|---|
| **Tanzania** | **Three months from proposal-form signature, or one month from receipt of the policy — whichever is later** | Proposal form signed / policy received | Insurance Act [CAP. 394 R.E. 2023] s.119 **[P]** |
| UK | 30 days (pure protection); 14 days (other) | Later of contract conclusion and receipt of terms | FCA ICOBS 7.1.1, 7.1.5 **[P]** |
| US (NY, representative) | "not less than ten days nor more than thirty days" | From the date the policy was delivered to the policy owner | NY Insurance Law §3203 **[P]** |
| US (NAIC) | Model 580 does not itself mandate a free look; it references "an unconditional refund provision of at least ten (10) days" as the condition under which the Buyer's Guide may be delivered with the policy instead of before | — | NAIC Life Insurance Disclosure Model Regulation **[P]** |

**Tanzania's rule is materially different from the others and is the one that binds this platform.** Insurance Act [CAP. 394 R.E. 2023] s.119, "Cancellation of life policy within limited penalty" **[P]**:

> "A life policy issued after the commencement of this Act may be cancelled by the policy-holder **within a period of three months from the date on which the proposal form was signed, or within one month of the receipt for the policy by the owner, whichever is the later** by returning the policy to the insurer with an objection in writing to any terms or conditions of the policy, or a statement that he does not require the policy, and the insurer shall **forthwith refund any premium which has been paid** in respect of the policy which shall thereupon be canceled."

Three consequences for any Tanzanian life system:

1. **The proposal form's signature date is a legally load-bearing field on the contract.** You cannot compute the s.119 window without it. ACORD's `SignedDate` on `ApplicationInfo` is exactly this field.
2. **The policy receipt date is equally load-bearing** — ACORD's `PolicyDeliveryReceiptDate` / `PolicyDeliveryReceiptInd`.
3. The window is `max(signed + 3 months, receipt + 1 month)` — so it can still be open **months** after issuance, and unlike NY there is no proportionate deduction in the statutory text: "forthwith refund any premium which has been paid".

### 5.4 Other Tanzanian requirements found

From the Insurance Regulations 2009, Part C (Code of Conduct and Ethics for Insurance Companies) **[P]** — these are binding on the *proposal form artefact*, so they belong in the product/forms layer, not just in UI copy:

- C.1 — "A proposal form shall contain a prominent statement that a specimen copy of the policy form and other terms applicable to risk are available on request."
- C.2 — proposal forms must prominently advise the policyholder to keep a record of all information supplied to the insurer.
- C.3 — the form must prominently state that a copy of the completed form is provided at completion, or will be supplied as normal practice, or on request.
- **C.4 — "An insurer shall not raise an issue under the proposal form, unless the policyholder is provided with a copy of the completed form."** This is a hard evidentiary gate: non-disclosure cannot be relied on unless the completed proposal was given to the policyholder. It maps to NAIC 565 §C's "a copy of the application... shall be attached to the policy when issued" **[P]** and to NY §3203's entire-contract rule.
- C.7 — a life company must give written information as to whether surrender values exist in the contract.
- Part B.4 (intermediaries) — the intermediary must "avoid influencing the client and make it clear that all the answers or statements are the latter's own responsibility" and "ensure that the consequences of non-disclosure and inaccuracies are pointed out to the client".

Also from the Act **[P]**: s.117 paid-up entitlement after three years' premiums; a proof-of-age notice must be issued *with* the policy; and misstatement-of-age provisions that turn on "any written statement made in the proposal or application for the policy as to the age or date of birth of the insured" — again making the proposal a durable part of the contract record, not a discarded input.

---

## 6. What this means for us

### 6.1 The current state, stated factually

The platform has **two independent code paths that each create a `policy.policy` row**:

1. **`POST /policies/manual-issue`** (`PolicyController.manualIssue`, `@PreAuthorize("hasRole('REALM_STAFF')")`) — takes a `ManualIssueRequestDto` with a mandatory `@NotBlank reasonForManualIssue` and a **`@NotNull underwritingCaseId`**, and calls `policyApi.issuePolicy(request.underwritingCaseId(), issueRequest, jwt.getSubject())`. The controller does apply `@Valid`, so the field is genuinely enforced at the wire — see 6.1a for why that enforcement buys nothing.
2. **`UnderwritingDecisionEventListener`** — on `underwriting.UnderwritingDecisionMade` with outcome `ACCEPT` or `LOADED`, it loads the decided case and calls `policyApi.issuePolicy(caseId, request, "system:underwriting-decision-listener")`.

Relevant facts about the linkage, from the code and migration:

- `Policy.underwritingCaseId` exists and is populated by both paths.
- `db-migrations/policy/V4__underwriting_case_id.sql` adds it as **`UUID` — nullable, no unique constraint, no foreign key**. Its own comment says: "Opaque ref into underwriting. NULL for policies issued before M6 — consumers must fail closed."
- `UnderwritingCaseStatus` is `OPEN, IN_REVIEW, DECIDED`. There is no status meaning "decided and issued", so the case cannot itself record that a policy already exists for it.
- `Policy.status` starts `"PROPOSED"` and the constructor is followed by a transition to `"ACTIVE"`; the lifecycle is `PROPOSED / ACTIVE / SUSPENDED / LAPSED / REINSTATED / SURRENDERED / MATURED`.
- The listener's own error comment states that if automatic issuance fails, "issuance needs a manual retry (via `/policies/manual-issue`)". So the two paths are already, by design, interchangeable for the same case.

**Therefore:** nothing in the schema or the domain prevents `issuePolicy` being called twice with the same `underwritingCaseId` — once by the listener and once by a staff retry, or twice by staff. Two `policy.policy` rows, two policy numbers, one application. The listener's documented retry story makes this the *expected* operational sequence after a transient failure, not an exotic edge case.

### 6.1a The link is required, unvalidated, and synthesised by the client

The `@NotNull` above is satisfied structurally and means nothing in practice, because the staff console **fabricates the value**. `frontend/src/features/policies/policyIssueForm.ts`, in `toApiRequest`:

```ts
// See the module doc above: never validated server-side, synthesized here.
underwritingCaseId: crypto.randomUUID(),
```

Its module doc gives the reasoning, which was accurate when written:

> It is structurally required by the DTO, but `PolicyApiImpl.issuePolicy` never validates it — no existence check, nothing … There is also no underwriting-case list/search endpoint anywhere on this platform for a staff user to find a real one to reference.

Both halves of that reasoning need revisiting. The first is still true — `issuePolicy` performs no existence check on the case id, only on `policyholderPartyId` and `productVersionId`. The second is **now stale**: the staff console has a working underwriting queue over a real case search endpoint, so a staff user *can* obtain a genuine case id.

Three consequences, in ascending order of seriousness:

1. **Every manually issued policy references a case that does not exist.** The column's stated purpose in V4 is not audit but claims contestability — `UnderwritingApi.checkContestability` is keyed on a case id.
2. **It defeats V4's own fail-closed instruction.** The migration says consumers "must fail closed on NULL". A synthesised uuid is not NULL, so the fail-closed branch never fires; the lookup simply resolves to nothing and the wrong answer looks like a real one.
3. **It removes the only available duplicate-detection key.** Even a detective control (Option E) cannot group a manually issued policy with the case it actually came from, because the link is random by construction. This is the fact that makes Option C (idempotency alone) weaker than it first appears: there is currently no stable key to be idempotent *on*.

Note also that `underwriting.underwriting_case.proposal_number` already carries a **per-tenant partial unique index** (`db-migrations/underwriting/V4__proposal_identity.sql`). The natural key that section 4 identifies as the industry mechanism already exists on this platform — on the underwriting side only, with nothing carrying it across to the policy.

### 6.1b What Stage 1 changed, and what it deliberately left

Stage 1 (branch `underwriting-decision-step`) took Option B and part of the diagnosis above.

**Changed.** Assessing and deciding are separate acts: `submitAssessment` records evidence and
a non-binding `recommendationOutcome`, and `UnderwritingApi.decide` is the sole producer of
`UnderwritingDecisionMade`. A decision requires evidence, a reason, and a loading matching its
outcome; departing from the recommendation requires `SENIOR_UNDERWRITER`. Decisions now record
their author. The automatic issuance path stopped discarding the life assured and the proposed
commencement date the case already held — until this, every automatically issued policy named
the policyholder as the life assured, which is wrong for precisely the credit-life and
third-party business the field exists for. And one case can now issue exactly one policy,
enforced by a partial unique index plus a service check that names the existing policy number;
the console stopped fabricating the case id that had made that check unenforceable.

**Deliberately left, in rough order of consequence.**

- **Offer, acceptance and first premium.** A policy still goes in force the moment a decision
  is accepted, before the customer has agreed a premium or paid anything. `PolicyStatus.PROPOSED`
  exists and is still unreachable — `issuePolicy` calls `activate` in the same transaction. This
  remains the largest divergence from the flow in §1, and it means we carry risk, bill, pay
  commission, cede to the reinsurer and report new business for someone who may never pay.
- **The capture gap.** Nothing records the requested policy term, premium-paying term, payment
  frequency or beneficiary nominations at proposal, though all four sit on a real proposal form.
  So the automatic path still issues without them, and the manual path remains the only way to
  produce a complete policy — which is part of why staff reached for it.
- **Bypass types on manual issue** (`MIGRATION`, `GUARANTEED_ISSUE`, `CONVERSION`,
  `REINSTATEMENT`). §3 shows the industry types the bypass on the contract rather than omitting
  the record; ours is still free text in `reasonForManualIssue`.
- **The case-to-policy back-reference.** A case cannot say whether it produced a policy —
  `UnderwritingCaseStatus` has no value meaning issued. The console's case picker therefore
  lists already-issued cases and relies on the 409 to refuse them, which is legible but not
  the same as not offering them.
- **`NOT_TAKEN_UP`.** Around 15% of decided cases per the SOA data, and we model a
  decided-but-never-paid application as nothing at all.
- **s.119 free-look dates.** Neither the proposal-signing date nor the delivery date is
  captured, so the statutory window is uncomputable; and a free-look cancellation would run
  through surrender logic and refund a surrender value where the Act requires the full premium.
- **Re-opening a declined case.** Still impossible. Only `POSTPONED` can be reworked.
- **KYC** is not required to open a case or issue a policy anywhere in the backend; only the
  console's party picker filters to verified people.

### 6.2 What the industry pattern implies

Nothing in the research suggests the platform is wrong to have a back-office direct-issuance capability. ACORD models exactly that, several times over: `Block Conversion` (63) for migrations, `Conversion - No underwriting done` (9), `Guaranteed Issue` (3), `Field Issue` (4), `Reinstatement` and `Reissue` application types. **A manual-issue path is normal and necessary.**

What the research does say, consistently across every source reached:

1. **The bypass is a labelled type on the contract, not the absence of a record.** ACORD types every non-underwritten issue (`OLI_LU_POLISSUE`) and every non-new-business application (`OLI_LU_APPTYPE`). Our `reasonForManualIssue` is free text; ACORD's equivalent is an enumerated code that stays queryable for the life of the contract and is available at claim time.
2. **Uniqueness is carried by identity, not by validation.** Socotra: the policy has the same locator as the quote. ACORD: the policy number *replaces* the application number as the handle on one `Holding`. Neither relies on a duplicate check.
3. **There is exactly one authority that mints the contract identifier.** ACORD 106 exists solely so that a policy number is *requested from the carrier*. We currently mint a policy number in two places.
4. **Approval and issuance are separate gates, and the gap between them is a modelled state.** `Approve - Issue Hold` (103), `Eligible, Issue Pending` (34). Our `UnderwritingCaseStatus.DECIDED` collapses "decided" and "acted upon" into one value, which is why the system cannot tell a case awaiting issuance from a case already issued.
5. **Not-taken-up is 15% of real cases** (SOA/Milliman **[P]**) and we have no state for it. A policy that goes `PROPOSED → ACTIVE` immediately cannot represent an issued-but-unplaced contract, and s.119 cancellation is not `SURRENDERED`.

### 6.3 Design options

Presented with trade-offs; not ranked, and no recommendation.

---

**Option A — One record, one lifecycle (the ACORD shape).**
The `Policy` row is created at proposal and carries the whole lifecycle: `PROPOSED → UNDERWRITING → APPROVED → ISSUED → ACTIVE`, with off-ramps `DECLINED`, `POSTPONED`, `NOT_TAKEN_UP`, `FREE_LOOK_CANCELLED`. Underwriting operates *on* the policy record rather than producing one. Manual issue becomes "create a policy that enters at `ISSUED` with an issue-type code".

- *For:* structurally impossible to get two policies from one application, because there is only ever one row. Matches the standard the industry's messaging is built on. NTU, free-look cancellation and issue-hold all become expressible. One place mints a policy number.
- *Against:* the largest change by far. `Policy` currently means "an in-force contract" throughout billing, claims, distribution, finaccounting and regreporting; making it also mean "a pending proposal" means every consumer must now filter by status, and every one of those filters is a place to get it wrong. Policy numbers would be minted for cases that never become contracts (ACORD handles this with the `TrackingID` → `PolNumber` handover, which is extra machinery). Reporting counts of "policies" change meaning.

---

**Option B — Two aggregates, one mandatory unique link.**
Keep `UnderwritingCase` and `Policy` distinct, but make the link total and unique: `underwriting_case_id` becomes `NOT NULL UNIQUE` on new rows, and *every* issuance must name a case. Manual issue auto-creates a case with an enumerated bypass type (`GUARANTEED_ISSUE`, `MIGRATION`, `CONVERSION`, `REINSTATEMENT`, `SIMPLIFIED_ISSUE`) that is `DECIDED` at creation.

- *For:* the database enforces one-policy-per-case with a single index; a retry after a failed listener run gets a constraint violation rather than a second policy. Preserves today's aggregate boundaries and the Spring Modulith module split. Turns `reasonForManualIssue` free text into the enumerated issue-type ACORD uses. Every policy gains a real contestability anchor, which the V4 migration comment says claims currently cannot rely on.
- *Against:* requires deciding what to do with existing NULL rows — a partial unique index (`WHERE underwriting_case_id IS NOT NULL`) leaves the legacy hole open; a backfill has no source of truth (the V4 comment says so explicitly). Creating a "case" for a data migration is arguably a fiction, though ACORD's `Block Conversion` status suggests the industry finds that fiction useful. Group schemes (`V9`) have their own `underwriting_case_id` column and would need the same treatment.

---

**Option C — Idempotency on the write path.**
Leave the model alone. Make `issuePolicy` idempotent: an `application_reference` (or the case id) as a natural key with a unique index, plus an idempotency key on `POST /policies/manual-issue`, so a repeat call returns the already-created policy rather than making another.

- *For:* smallest change; directly addresses the documented retry scenario in `UnderwritingDecisionEventListener`. Standard, well-understood HTTP semantics. Does not disturb the meaning of `Policy` anywhere downstream.
- *Against:* solves *accidental* duplication only. Two staff members issuing two genuinely different policies for one proposal on two different days would supply different idempotency keys and both would succeed. Does nothing for the missing NTU/free-look states, nothing for the enumerated bypass type, and nothing for the "one authority mints the number" problem. It is a guard, not a model.

---

**Option D — Collapse to a single issuance path; manual issue becomes a decision.**
`POST /policies/manual-issue` stops creating a policy. It creates (or takes) an underwriting case, records a `DECIDED` outcome with a bypass type, and lets the existing `UnderwritingDecisionEventListener` do the only issuance in the system.

- *For:* exactly one line of code creates a policy. Matches the sources' consistent separation of *decide* from *issue* (§2.3), and the SOA finding that STP and manual paths must coexist but converge. Every policy automatically acquires an audit trail of who decided what and why, satisfying the group-EOI control lesson from the DOL settlements (§3.2).
- *Against:* the listener runs `AFTER_COMMIT` in a `REQUIRES_NEW` transaction and swallows exceptions to a log line — making it the sole issuance path means a synchronous staff request now depends on a fire-and-forget listener that cannot report failure to the caller. Its own javadoc records that an earlier version of this arrangement silently produced zero rows. Would need the listener made synchronous or given a real outbox with retry and dead-lettering before it could carry this load. Also inverts the "staff override" ergonomics: staff would issue by *deciding*, which reads oddly at a console.

---

**Option E — Detective control only.**
Keep both paths; add a reconciliation report and a warning banner when a case already has a policy.

- *For:* essentially free; no migration, no model change. Would surface the size of the problem in existing data before anyone commits to a structural fix.
- *Against:* detective, not preventive — the duplicate contract exists, has a number, may have been delivered, may be billing, and may be reinsured before anyone reads the report. Given TIRA s.119's three-month cancellation window and the two-year contestability period, a duplicate can be discovered long after both are legally live.

---

**Cross-cutting items, independent of which option is chosen:**

- **Record the proposal-form signed date and the policy delivery-receipt date on the policy.** TIRA s.119 makes the free-look window `max(signed + 3 months, receipt + 1 month)`; neither date is currently captured, so the statutory window is not computable. This is a compliance gap regardless of the duplicate question.
- **Add `NOT_TAKEN_UP` and a free-look cancellation state.** 15% of decided cases are NTU (SOA/Milliman **[P]**), and `SURRENDERED` is the wrong word for a s.119 cancellation — the premium is refunded in full and the contract is treated as cancelled, not surrendered for value.
- **Enumerate the manual-issue reason.** `@NotBlank String reasonForManualIssue` is free text; the industry equivalent is a closed typecode that survives to claim time (§3).
- **Distinguish additional from alternate applications** if the platform will ever let one person hold several policies (§4.4). Without that flag, aggregate-exposure checks and duplicate detection cannot be told apart.

---

## Sources

### Primary [P]

**ACORD (first-party public Fact Sheets)**
- *Life & Annuity Program — Industry New Business Process*, Fact Sheet v02 — https://www.acord.org/docs/default-source/standards-la-factsheets/acord_la_industrylifeannuitynewbusinessprocess_factsheet_v02.pdf?sfvrsn=c40206fd_8
- *Life & Annuity New Business — 103 New Business Submission*, Fact Sheet v01 — https://www.acord.org/docs/default-source/standards-la-factsheets/acord_la_newbusiness103newbusinesssubmission_factsheet_v01.pdf?sfvrsn=7d9e7355_8
- *Life & Annuity Program — Industry Inforce Administration*, Fact Sheet v03 — https://www.acord.org/docs/default-source/standards-la-factsheets/acord_la_industrylifeannuityinforceadmin_factsheet_v03.pdf?sfvrsn=3c058ae5_14
- *ACORD Standardized Life Insurance Application*, Forms Fact Sheet — https://www.acord.org/docs/default-source/standards-la-factsheets/acord_standardized_life_application_fact_sheet.pdf?sfvrsn=7521d7bd_24
- *Life & Annuity Data Standards* (landing page; returned 403 to automated fetch) — https://www.acord.org/standards-architecture/acord-data-standards/Life_Annuity_Data_Standards

**Socotra (first-party developer documentation)**
- Quotes / quote lifecycle — https://docs.socotra.com/featureGuide/policyQuotation/quotes.html
- Underwriting plugin — https://docs.socotra.com/configuration/plugins/underwriting.html
- Execute a quote to bind — https://docs.socotra.com/getting-started/execute-a-quote-to-bind

**Regulators and statute**
- Tanzania, **The Insurance Act [CAP. 394 R.E. 2023]** — s.119 cancellation, s.117 paid-up, misstatement of age — https://www.tira.go.tz/uploads/documents/sw-1754919729-THE%20INSURANCE%20ACT%20%5BCAP.%20394%20R.E.%202023%5D.pdf
- Tanzania, **The Insurance Regulations, 2009** — Parts B and C codes of conduct, proposal-form requirements — https://www.tira.go.tz/uploads/documents/en-1660295576-Insurance%20Regulations%20of%202009.pdf
- NAIC, **Group Life Insurance Definition and Standard Provisions Model Act** (MO-565) — https://content.naic.org/sites/default/files/model-law-565.pdf
- NAIC, **Life Insurance Disclosure Model Regulation** (MO-580) — https://content.naic.org/sites/default/files/model-law-580.pdf
- FCA Handbook, **ICOBS 7** (cancellation) — https://www.handbook.fca.org.uk/handbook/ICOBS/7/?view=chapter
- New York Insurance Law **§3203** (standard provisions; entire contract; free look; incontestability) — https://www.nysenate.gov/legislation/laws/ISC/3203
- US DOL/EBSA settlement announcements on group life evidence-of-insurability practices (Unum, Lincoln National, United of Omaha, Prudential). Release pages returned 403 to automated fetch and the settlement PDF is a scanned image; terms cited in §3.2 come from DOL's own release text as surfaced in search and **should be verified against the release before being relied on** — https://www.dol.gov/newsroom/releases/ebsa/ebsa20240611

**Industry / actuarial bodies**
- SOA Research Institute (Milliman: Al Klein, Justin Li), **2022 Accelerated Underwriting Practices Survey Report**, November 2023 — https://www.soa.org/globalassets/assets/files/resources/research-report/2023/acc-underwriting-practices-survey.pdf
- US Bureau of Labor Statistics, *Occupational Outlook Handbook* — **Insurance Underwriters** — https://www.bls.gov/ooh/business-and-financial/insurance-underwriters.htm
- O*NET OnLine — **Insurance Claims and Policy Processing Clerks (43-9041.00)** — https://www.onetonline.org/link/summary/43-9041.01

**Open-source domain model**
- openIMIS policy documentation (policy status: idle / ready / active / suspended / expired) — https://docs.openimis.org/en/latest/user_manual/insuree_policies/policy.html

### Primary-mirror [P-mirror]

The ACORD OLifE object and typecode definitions quoted throughout are from the **PilotFish ACORD Model Viewer**, a public republication of ACORD's Life, Annuity & Health model carrying ACORD's own definition text. It is not ACORD's own site; ACORD's authoritative versions are members-only. Every quotation attributed to `[P-mirror]` was read from these pages:

- `OLI_LU_POLSTAT` (Policy Status, ~90 codes) — https://modelviewers.pilotfishtechnology.com/modelviewers/ACORD/model/typecodes/OLI_LU_POLSTAT.html
- `OLI_LU_POLISSUE` (Policy Issue Type) — https://modelviewers.pilotfishtechnology.com/modelviewers/ACORD/model/typecodes/OLI_LU_POLISSUE.html
- `OLI_LU_POLISSUESUB` (Policy Issue Sub Type) — https://modelviewers.pilotfishtechnology.com/modelviewers/ACORD/model/typecodes/OLI_LU_POLISSUESUB.html
- `OLI_LU_APPTYPE` (Application Type) — https://modelviewers.pilotfishtechnology.com/modelviewers/ACORD/model/typecodes/OLI_LU_APPTYPE.html
- `ApplicationInfo` — https://modelviewers.pilotfishtechnology.com/modelviewers/ACORD/model/ApplicationInfo.html
- `Policy` — https://modelviewers.pilotfishtechnology.com/modelviewers/ACORD/model/Policy.html
- `Holding` — https://modelviewers.pilotfishtechnology.com/modelviewers/ACORD/model/Holding.html
- `TrackingID`, `HOAssignedAppNumber`, `FormalAppInd`, `AdditionalInd`, `AlternateInd`, `PolNumber` — sibling element pages under the same path
- Liquid Technologies ACORD Life Standards 2.20.01 XSD documentation (used only to confirm the TXLife request/response envelope; the OLifE object definitions live in the included `XMLife2.20.01.xsd`, which was not reachable) — https://schemas.liquid-technologies.com/Accord/Life%20Standards/2.20.01/txlife.html

### Secondary [S]

None of the substantive claims above rest on a secondary source. Search-result summaries were used only to locate primary documents, and where a primary document could not be opened (the DOL settlement PDF, the ACORD landing page) that is stated at the point of use rather than backfilled with a secondary description.

### Not reachable / open gaps

- ACORD transaction specifications and implementation guides (103 NBfL, 1125 Pending Case Status, 106 Policy Number Request) — members-only.
- Swiss Re *Life Guide* and RGA underwriting material — gated / host unreachable.
- Oracle OIPA documentation — docs.oracle.com unreachable from this environment throughout.
- A primary-source definition of **"actively at work"** in group life. Not found in NAIC Model 565 or any reachable statute. Currently an unsourced industry convention in this note.
- Any **TIRA circular or guideline** specific to new-business processing, policy-issuance turnaround, or e-issuance. The Act and the 2009 Regulations were reached; no such circular was.
- An explicit **idempotency mechanism** in Socotra's public API docs. Not found; their state machine appears to make it unnecessary, but absence is recorded rather than interpreted.
