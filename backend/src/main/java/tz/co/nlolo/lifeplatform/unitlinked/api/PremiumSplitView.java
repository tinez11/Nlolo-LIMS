package tz.co.nlolo.lifeplatform.unitlinked.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One version of a policy's premium split (U2, spec §4): where each premium received from {@code effectiveFrom} goes. */
public record PremiumSplitView(UUID splitId, String policyNumber, Instant effectiveFrom, List<Share> shares,
                               String recordedBy, Instant recordedAt) {

    public record Share(String fundCode, int percent) {}
}
