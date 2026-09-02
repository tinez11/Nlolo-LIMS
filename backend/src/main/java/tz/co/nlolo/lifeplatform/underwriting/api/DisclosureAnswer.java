package tz.co.nlolo.lifeplatform.underwriting.api;

/**
 * One question an applicant was asked, and what they answered.
 *
 * <p>The platform deliberately does NOT own the question set. Which questions a proposal form
 * asks is product-specific and, in this market, shaped by what TIRA and the reinsurer require —
 * inventing a canonical list here would be inventing underwriting policy. What the platform owns
 * is the RECORD: that this question, in these words, was put to this applicant, and this is what
 * they said.
 *
 * <p>That record is the point. A claim can be contested for non-disclosure, and
 * {@code Claim.requiresContestabilityReview} is computed and shown on the claims screen today —
 * but nothing on this platform held any evidence of what was ever disclosed, so a contestability
 * review had nothing to review. The {@code medical_disclosure} table has existed since M4 with
 * zero call sites.
 *
 * @param questionCode  the product's own identifier for the question, so answers stay comparable
 *                      across applications even when the wording is edited.
 * @param question      the wording actually put to the applicant. Stored rather than looked up:
 *                      contesting a claim turns on what the person was asked at the time, not on
 *                      what the current version of the form says.
 * @param answer        what they said. Free text, not a boolean: "have you ever been treated for
 *                      heart disease" is often answered with a qualification, and flattening that
 *                      to yes/no discards the part a reviewer needs.
 * @param notes         anything the person recording it added — context an assessor gave, or a
 *                      note that a document was produced. Null when there is nothing to add.
 */
public record DisclosureAnswer(String questionCode, String question, String answer, String notes) {}
