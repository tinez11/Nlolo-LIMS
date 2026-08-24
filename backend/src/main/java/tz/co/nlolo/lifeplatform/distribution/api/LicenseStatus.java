package tz.co.nlolo.lifeplatform.distribution.api;

/** Matches {@code distribution.agent_profile.license_status}'s CHECK constraint exactly
 * (db-migrations/distribution/V1:11). */
public enum LicenseStatus {
    ACTIVE,
    EXPIRED,
    SUSPENDED
}
