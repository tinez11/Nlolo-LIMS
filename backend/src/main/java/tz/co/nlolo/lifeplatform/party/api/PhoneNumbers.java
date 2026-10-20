package tz.co.nlolo.lifeplatform.party.api;

import java.util.regex.Pattern;

/**
 * A Tanzanian phone number, written however a person writes it, stored one way (2026-10-20).
 *
 * <p>People write {@code 0712 345 678}, {@code 255712345678}, {@code +255 712-345-678} or {@code 00255712345678}. The
 * platform stores exactly one form, E.164 with the plus -- {@code +255712345678} -- because everything downstream
 * relies on it: the SMS adapter strips the plus to make the aggregator's MSISDN and would send a {@code 07...} number
 * as written, where it is accepted and never arrives; mobile money and searching for a client match on it too. So the
 * everyday forms are accepted at the door and converted here, before anything is saved, and the stored value never
 * changes shape.
 */
public final class PhoneNumbers {

    /** The one stored form. */
    public static final Pattern E164 = Pattern.compile("^\\+255\\d{9}$");

    /** Spaces, dashes, dots and brackets people put in a number to read it. */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-.()]");

    private PhoneNumbers() {}

    /**
     * {@code +255XXXXXXXXX} for any accepted form; null for a blank, which is "no phone", not an error.
     *
     * @throws IllegalArgumentException for anything that is not a Tanzanian number, in words a person can act on
     */
    public static String normalise(String written) {
        if (written == null || written.isBlank()) return null;
        String digits = SEPARATORS.matcher(written.trim()).replaceAll("");
        String national;
        if (digits.startsWith("+255")) national = digits.substring(4);
        else if (digits.startsWith("00255")) national = digits.substring(5);
        else if (digits.startsWith("255")) national = digits.substring(3);
        else if (digits.startsWith("0")) national = digits.substring(1);
        else national = null;
        if (national == null || !national.matches("[1-9]\\d{8}")) {
            throw new IllegalArgumentException("Enter a Tanzanian phone number, e.g. 0712 345 678 or +255712345678 -- not \""
                + written.trim() + "\"");
        }
        return "+255" + national;
    }
}
