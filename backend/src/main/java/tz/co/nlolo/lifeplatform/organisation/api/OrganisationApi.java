package tz.co.nlolo.lifeplatform.organisation.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The company, as the rest of the platform reads it (2026-11-09, organisation settings). Every read is the current
 * tenant's.
 *
 * <p>The document reads never throw: a receipt must print even when the branding cannot be read just then, so they fall
 * back to {@link Letterhead#fallback()} and no signature.
 */
public interface OrganisationApi {

    Letterhead letterhead();

    /** The signatory set to sign this kind of document who was in force on {@code onDay}, if any. */
    Optional<SignatureStamp> signatory(DocumentKind kind, LocalDate onDay);

    /** The company's branches, the head office first; seeded from the shared BRANCH list on first read. */
    List<BranchView> branches();

    /** Whether {@code code} is one of the company's active branches. */
    boolean isActiveBranch(String code);

    /** The head office's branch code: the branch a sale takes when nothing else names one. */
    String headOfficeBranch();
}
