package tz.co.nlolo.lifeplatform.distribution.api;

/**
 * What giving a login, or a new password, did (2026-10-28) -- the portal invite's shape. {@code temporaryPassword} is
 * returned once, only when nothing could carry it to the agent; {@code smsSentTo} names the phone a one-time password
 * went to; {@code notice} says why a text that should have gone did not.
 */
public record AgentLoginIssued(AgentLoginView login, String temporaryPassword, String smsSentTo, String notice) {}
