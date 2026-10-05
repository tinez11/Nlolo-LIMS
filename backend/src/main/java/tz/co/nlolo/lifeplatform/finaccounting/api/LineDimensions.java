package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.util.UUID;

/**
 * The dimensions every journal line carries (IFRS 17 posting guide 2.2): the IFRS 17 group, its measurement model,
 * the movement type (PRM_REN, IC_SUR, UL_SWITCH ... guide 2.4), product, portfolio, channel, branch, fund, and a
 * reference to the source record. Each may be null: classification at sale (I2) and the rules engine (I3) fill them,
 * and a line with no policy behind it has no group.
 */
public record LineDimensions(String ifrs17Group, String measurementModel, String movementType, UUID productId,
                             String portfolio, String channel, String branch, String fund, String referenceType,
                             String reference) {

    /** A line with no dimensions recorded yet. */
    public static final LineDimensions NONE = new LineDimensions(null, null, null, null, null, null, null, null, null, null);

    /** Just a movement type: what a rule knows before a policy's classification is joined on. */
    public static LineDimensions movement(String movementType) {
        return new LineDimensions(null, null, movementType, null, null, null, null, null, null, null);
    }
}
