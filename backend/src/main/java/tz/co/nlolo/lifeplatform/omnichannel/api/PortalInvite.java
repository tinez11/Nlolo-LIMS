package tz.co.nlolo.lifeplatform.omnichannel.api;

/**
 * The outcome of inviting (or re-inviting) a client to the portal.
 *
 * @param temporaryPassword the one-time password for a client with no email, shown to the staff member ONCE and never
 *     stored or sent anywhere by the platform -- the message log keeps every message body, so an SMS would have put
 *     the password in the database. Null when Keycloak emailed a set-your-password link instead.
 */
public record PortalInvite(PortalAccessView access, String temporaryPassword) {}
