package tz.co.nlolo.lifeplatform.product.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The product exists; no version of it is in force on the date asked about.
 *
 * <p>Its own exception because {@link ProductNotFoundException} was answering two
 * different questions with one word. "There is no such product" and "this product's
 * version starts next week" send whoever asked to completely different places, and the
 * second is a NORMAL state: publishing a version ahead of its effective date is exactly
 * what product authoring is for, and a version carries a retirement date so a product
 * can also have outlived every version it ever had.
 *
 * <p>Conflating them produced a real defect on the console. A GROUP_LIFE product whose
 * only version was effective the following day appeared in the catalogue -- publishing
 * flips the definition to ACTIVE regardless of when the version starts -- and opening it
 * reported "this record does not exist, or it is not available to your role", to a
 * staff member who had just clicked it in a list. Both halves of that sentence were
 * false, and the second invited them to go and ask about their permissions.
 */
public class NoActiveProductVersionException extends RuntimeException {

    private final UUID productId;
    private final LocalDate asOfDate;
    private final LocalDate nextEffectiveDate;

    public NoActiveProductVersionException(UUID productId, LocalDate asOfDate, LocalDate nextEffectiveDate) {
        super(message(asOfDate, nextEffectiveDate));
        this.productId = productId;
        this.asOfDate = asOfDate;
        this.nextEffectiveDate = nextEffectiveDate;
    }

    /**
     * Written to be read by a person, because it is: this reaches the console as the
     * ProblemDetail's {@code detail} and is rendered verbatim on the product record.
     *
     * <p>So it names no productId, which is why the id is a field rather than part of the
     * text. The caller asked about one product, by id, in the path it called; repeating it
     * here only puts a UUID in front of somebody who is looking at the product's name at
     * the top of the same screen, which is the raw-id habit this console spent a whole
     * pass removing. The id stays available to a log or a handler through the getter.
     *
     * <p>Dates stay ISO. That is unambiguous, which is the actual requirement -- the
     * platform's day-first convention exists because {@code 03/04/2026} can be read two
     * ways, and {@code 2026-09-05} cannot be.
     */
    private static String message(LocalDate asOfDate, LocalDate nextEffectiveDate) {
        String base = "No version of this product is in force on " + asOfDate;
        if (nextEffectiveDate != null) {
            return base + "; the next takes effect on " + nextEffectiveDate + ".";
        }
        return base + ", and no later version is scheduled.";
    }

    public UUID getProductId() {
        return productId;
    }

    public LocalDate getAsOfDate() {
        return asOfDate;
    }

    /** Null when nothing later is scheduled -- a product with no future version at all. */
    public LocalDate getNextEffectiveDate() {
        return nextEffectiveDate;
    }
}
