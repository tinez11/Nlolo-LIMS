package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.time.Instant;

/**
 * Who has signed off the accounting-policy baseline (2026-11-12, finance findings 99 and 66): finance, and an actuary
 * who may co-sign. Null where no one has yet.
 */
public record BaselineSignOffView(Signature finance, Signature actuary) {

    public record Signature(String signedBy, Instant signedAt, String reference) {}
}
