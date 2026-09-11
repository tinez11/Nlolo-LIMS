package tz.co.nlolo.lifeplatform.party.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Correcting a company's details.
 *
 * <p>Its own request rather than reusing {@link RegisterCorporateRequest}, and the difference is
 * the whole reason: <b>there is no registrationNumber here</b>. A company's registration number
 * is its identity in the national register — the thing a duplicate check runs on, and the thing
 * a policy was underwritten against. Correcting a phone number must not be a route to quietly
 * becoming a different company.
 *
 * <p>If a registration number really was recorded wrongly, that is a deliberate act with its own
 * evidence, not a side effect of an edit form.
 */
public record AmendCorporateRequest(
    @NotBlank String registeredName,
    @Valid @NotNull ContactInfo contactInfo) {}
