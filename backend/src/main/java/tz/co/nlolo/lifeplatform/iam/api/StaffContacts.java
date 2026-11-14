package tz.co.nlolo.lifeplatform.iam.api;

import java.util.Optional;
import java.util.UUID;

/**
 * How to reach a staff member outside the console (2026-11-06): the phone on their staff record, for the "your password
 * was changed" text. Staff have no client record, so the platform's ordinary contact lookup cannot find them.
 */
public interface StaffContacts {

    /** The phone on the staff record of {@code userId} in the current tenant, when there is one. */
    Optional<String> phoneOf(UUID userId);

    /** The language of texts to {@code userId}: "en" or "sw" (2026-11-14, staff sign-in code); "en" when unknown. */
    String languageOf(UUID userId);
}
