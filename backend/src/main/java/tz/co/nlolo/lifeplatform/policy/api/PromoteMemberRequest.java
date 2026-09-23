package tz.co.nlolo.lifeplatform.policy.api;

import tz.co.nlolo.lifeplatform.party.api.IdentityDocument;
import tz.co.nlolo.lifeplatform.party.api.Sex;

/**
 * The identity a freeform member is promoted with.
 *
 * <p>The identity document is not optional and the API refuses a promotion without one.
 * Promoting somebody with no document registers a second unidentified person rather than
 * resolving the one we have — which is worse than leaving them freeform, because it looks
 * resolved and is not.
 *
 * @param identityDocument what proves who they are. Also how an existing customer is found,
 *     since the borrower may already bank with the lender.
 * @param phoneNumber contact details, nullable — a deceased borrower's phone is often unknown
 *     and refusing the promotion over it would block the claim.
 * @param sex nullable for the same reason. Credit life is not rated on it (§3), so it is
 *     recorded where known and never required.
 */
public record PromoteMemberRequest(IdentityDocument identityDocument,
                                    String phoneNumber,
                                    Sex sex) {}
