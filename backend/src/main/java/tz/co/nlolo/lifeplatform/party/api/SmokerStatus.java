package tz.co.nlolo.lifeplatform.party.api;

/**
 * Whether a person smokes, as declared on the party record.
 *
 * <p>{@code UNKNOWN} is a real recorded value, not a null stand-in: it means the
 * question was put and not answered, which a product may price deliberately.
 * "Nobody asked" is the null, and the two must stay distinguishable -- which is
 * why the column carries no database default.
 *
 * <p><b>Deliberately duplicated</b> from {@code product.api.SmokerStatus}; see
 * {@link Sex} for why the duplication is correct rather than accidental.
 */
public enum SmokerStatus {
    SMOKER,
    NON_SMOKER,
    UNKNOWN
}
