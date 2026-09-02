package tz.co.nlolo.lifeplatform.party.api;

/**
 * A person's sex, as recorded on the party record.
 *
 * <p>Deliberately named {@code Sex}, not {@code Gender}, and deliberately narrow:
 * it exists because mortality differs measurably between the two, which is a
 * biological rating input rather than an identity field.
 *
 * <p><b>Deliberately duplicated</b> from {@code product.api.Sex}, which has the
 * same two literals. This is not an oversight and should not be "fixed" by making
 * one import the other. The {@code party} module allows exactly {@code
 * document::api} and {@code refdata::api}; every module that depends on {@code
 * product} does so in the other direction, so borrowing the enum would invert a
 * dependency the modulith test enforces. It is also the better model: here this is
 * an attribute of a person, there it is a dimension of a rate table. The mapping
 * belongs to whoever asks for a price -- {@code policy} and {@code underwriting}
 * both already allow {@code party::api} and {@code product::api}.
 */
public enum Sex {
    FEMALE,
    MALE
}
