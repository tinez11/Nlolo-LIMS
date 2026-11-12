package tz.co.nlolo.lifeplatform.policy.api;

import java.util.UUID;

/**
 * A policy a client holds, with only what a list of their premiums needs (2026-11-12, the client Premiums tab): the
 * full {@link PolicyView} is built per policy and took most of the time for a client with 176 policies.
 */
public record HeldPolicy(String policyNumber, UUID productId, String premiumFrequency) {}
