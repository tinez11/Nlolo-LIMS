package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The allowlist is shared by every upload path on the platform, so widening it for one
 * caller is a change to all of them. Tested as a pure function with no Spring.
 */
class AllowedDocumentContentTypesTest {

    @Test
    void aCsvIsAllowedBecauseALenderSchedulesArriveAsOne() {
        assertEquals("text/csv", AllowedDocumentContentTypes.normalizeOrThrow("text/csv"));
    }

    @Test
    void parametersAreStillDroppedFromACsvType() {
        // The allowlist exists to guarantee what is persisted is one short literal --
        // text/csv;charset=<script> must not become a stored value.
        assertEquals("text/csv", AllowedDocumentContentTypes.normalizeOrThrow("text/csv;charset=utf-8"));
    }

    @Test
    void anUnknownTypeIsStillRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> AllowedDocumentContentTypes.normalizeOrThrow("application/zip"));
    }

    @Test
    void theExistingFourAreUntouched() {
        // Widening this set for credit life must not quietly drop what claims and KYC
        // already rely on.
        assertEquals("image/jpeg", AllowedDocumentContentTypes.normalizeOrThrow("image/jpeg"));
        assertEquals("image/png", AllowedDocumentContentTypes.normalizeOrThrow("image/png"));
        assertEquals("application/pdf", AllowedDocumentContentTypes.normalizeOrThrow("application/pdf"));
        assertEquals("application/octet-stream",
            AllowedDocumentContentTypes.normalizeOrThrow("application/octet-stream"));
    }
}
