package tz.co.nlolo.lifeplatform.party.api;

import java.time.LocalDate;
import java.util.UUID;

public record GroupMembershipView(UUID memberPartyId, LocalDate joinDate, String status) {}
