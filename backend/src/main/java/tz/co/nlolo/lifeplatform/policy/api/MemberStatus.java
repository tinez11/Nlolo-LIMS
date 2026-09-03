package tz.co.nlolo.lifeplatform.policy.api;

/**
 * Whether a member is currently on a scheme.
 *
 * <p>An exited member is never deleted: a claim can arrive after someone leaves the
 * employer, and "were they covered on the date of event" needs the dates to still exist.
 */
public enum MemberStatus { ACTIVE, EXITED }
