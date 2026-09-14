package tz.co.nlolo.lifeplatform.policy.api;

import java.util.UUID;

/**
 * Mapped to 422 Unprocessable Entity -- a policy was issued naming an {@code agentOfRecordId}
 * that is not an agent in this tenant.
 *
 * <p><b>Why this is refused rather than accepted and ignored.</b> An agent of record typed by
 * hand used to be stored as an opaque id and never checked. A wrong one is not inert: the policy
 * is attributed to nobody, {@code PolicyEventListener} logs "does not resolve to an agent ... no
 * commission accrued" into a server log the person issuing it will never read, and the sale earns
 * the agent nothing. Nothing on screen ever says so. This platform's own dev database carries six
 * policies pointing at one phantom agent id, three of them ACTIVE, with zero accruals between
 * them -- the whole failure is silent.
 *
 * <p>422 rather than 404, because the agent is a field in a submitted record, not the thing being
 * addressed: the request is well-formed and the policy it describes is refusable on its content.
 * That matches {@code BeneficiaryValidationException}'s treatment of a beneficiary that does not
 * resolve.
 *
 * <p>Only ever raised for a value a CALLER supplied. An agent bound from the client's registering
 * agent is resolved from the party record, not typed, and cannot be wrong this way.
 */
public class UnknownAgentOfRecordException extends RuntimeException {
    public UnknownAgentOfRecordException(UUID agentOfRecordId) {
        super("Agent of record " + agentOfRecordId + " is not an agent in this tenant; "
            + "a policy attributed to an unknown agent would accrue no commission to anybody");
    }
}
