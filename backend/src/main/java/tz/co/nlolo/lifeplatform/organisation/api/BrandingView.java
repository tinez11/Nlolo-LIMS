package tz.co.nlolo.lifeplatform.organisation.api;

/**
 * How the company's documents look (2026-11-09). Also what a save sends; {@code hasLogo} is read-only.
 *
 * @param typeface SANS or SERIF
 * @param layout   CLASSIC, BANDED or MINIMAL
 * @param paper    A4 or LETTER
 */
public record BrandingView(String primaryColour, String secondaryColour, String typeface, String layout, String paper,
                           String footerText, String reportConfidentiality, boolean hasLogo) {}
