package tz.co.nlolo.lifeplatform;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * How to name the caller to another person: {@code name} ("Asha Admin"), else
 * {@code preferred_username} ("staff.admin"), else null.
 *
 * <p>Only ever a LABEL, captured at the moment of an act so a record can say who did it -- who
 * registered a client, who set up a scheme on agreed terms. The SUBJECT stays the identity every
 * rule compares: a display name is neither unique nor stable, and access or separation of duties
 * decided on one could be defeated by two people who share a name.
 *
 * <p>The same rule as claims' own {@code ClaimController.displayName}, which predates this.
 */
public final class TokenNames {

    private TokenNames() {}

    public static String displayName(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        for (String claim : new String[] {"name", "preferred_username"}) {
            String value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }
}
