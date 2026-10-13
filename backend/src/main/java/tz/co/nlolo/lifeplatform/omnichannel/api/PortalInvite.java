package tz.co.nlolo.lifeplatform.omnichannel.api;

/**
 * The outcome of inviting (or re-inviting) a client to the customer portal.
 *
 * @param temporaryPassword the one-time password, shown to the staff member ONCE and stored nowhere: for a client with
 *     no email and no phone to text, or when the SMS could not be sent. Null when a link was emailed or the password
 *     was texted -- in which case the platform itself never shows it to anyone but the client.
 * @param smsSentTo the number the one-time password was texted to; null when it was not texted
 * @param notice why the staff member is being shown the password after all (the SMS was not sent, with the gateway's
 *     own reason); null when nothing went wrong
 */
public record PortalInvite(PortalAccessView access, String temporaryPassword, String smsSentTo, String notice) {}
