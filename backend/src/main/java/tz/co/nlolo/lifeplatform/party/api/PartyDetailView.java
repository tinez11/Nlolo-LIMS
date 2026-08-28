package tz.co.nlolo.lifeplatform.party.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Everything the platform holds about one party, for the client register's detail screen.
 *
 * <p>Deliberately SEPARATE from {@link PartyView}, which stays four fields. {@code PartyView} is
 * what {@code GET /parties} returns for every row of a page and what four other modules read as an
 * existence check, so widening it would put a date of birth, a phone number and an email address on
 * the wire twenty at a time for a list that displays four columns — and would change a contract
 * claims, policy, underwriting and distribution all depend on. This view exists on exactly one
 * read, {@code GET /parties/{partyId}}, which is also the single place the agent-scoping rule has
 * to bite.
 *
 * <p>{@code createdBy} is the JWT subject of whoever registered the party. It is returned because
 * it is the field the agents realm is scoped on, so a staff user reading a client record can see
 * which agent owns the relationship without a second lookup.
 *
 * <p>Every field except {@code partyId}, {@code partyType}, {@code kycStatus} and {@code
 * displayName} may be null: an individual has no registration number, a corporate has no date of
 * birth, contact details are optional at registration, and {@code kycVerifiedAt} is only set once a
 * decision has actually been recorded.
 */
public record PartyDetailView(
    UUID partyId,
    PartyType partyType,
    KycStatus kycStatus,
    String displayName,
    LocalDate dateOfBirth,
    String registrationNumber,
    String phoneNumber,
    String email,
    Instant kycVerifiedAt,
    Instant createdAt,
    String createdBy) {}
