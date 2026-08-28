package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;

/**
 * One policy that names a given party as a beneficiary — the reverse of the beneficiary list on a
 * policy, and the answer to "which policies pay out to this person".
 *
 * <p>Nothing on the platform could answer that before: {@code beneficiary} was only ever read by
 * {@code policyNumber}, so a party's exposure as a beneficiary was recorded and unreadable. It is
 * real customer-service information (a widow asking what she is owed) and real anti-fraud
 * information (one party named across an implausible number of policies).
 *
 * <p>{@code policyStatus} is carried because the share is meaningless without it — being named on
 * a LAPSED policy is not the same as being named on one in force, and a register that showed only
 * the percentage would imply a benefit that may not exist.
 *
 * <p>Only ACTIVE beneficiary rows appear. Replacing a policy's beneficiaries deactivates the old
 * rows rather than deleting them, so the inactive ones are a history of who used to be named — a
 * different question, and not one this view answers.
 */
public record BeneficiaryOfView(
    String policyNumber,
    String policyStatus,
    BigDecimal sharePercent,
    boolean revocable) {}
