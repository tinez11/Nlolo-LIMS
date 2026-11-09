package tz.co.nlolo.lifeplatform.organisation.api;

/**
 * Everything a document needs to say whose it is and to look like theirs (2026-11-09, organisation settings): the
 * company's names, registration and contacts, and its branding. Read once per document; never null.
 *
 * @param primaryColour   #RRGGBB: the letterhead, headings and table header
 * @param secondaryColour #RRGGBB: bands and accents
 * @param typeface        SANS or SERIF
 * @param layout          CLASSIC, BANDED or MINIMAL
 * @param paper           A4 or LETTER
 * @param logo            PNG or JPEG bytes, or null for none
 */
public record Letterhead(String legalName, String tradingName, String registrationNumber, String tin, String address,
                         String phone, String email, String website, String tagline, String primaryColour,
                         String secondaryColour, String typeface, String layout, String paper, String footerText,
                         String reportConfidentiality, byte[] logo) {

    public static final String DEFAULT_NAME = "Nlolo Life";
    public static final String DEFAULT_PRIMARY = "#17324D";
    public static final String DEFAULT_SECONDARY = "#0E8C7F";

    /** The name a document is headed with: the trading name where the company has one. */
    public String displayName() {
        return tradingName != null && !tradingName.isBlank() ? tradingName : legalName;
    }

    /** What a company that has set nothing prints: the platform's own name, in its own colours. */
    public static Letterhead fallback() {
        return new Letterhead(DEFAULT_NAME, null, null, null, null, null, null, null, null, DEFAULT_PRIMARY,
            DEFAULT_SECONDARY, "SANS", "CLASSIC", "A4", null, "Confidential: internal management information.", null);
    }
}
