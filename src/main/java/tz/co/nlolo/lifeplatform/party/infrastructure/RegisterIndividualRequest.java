package tz.co.nlolo.lifeplatform.party.infrastructure;

import java.time.LocalDate;

public record RegisterIndividualRequest(String fullName, LocalDate dateOfBirth, ContactInfo contactInfo) {}
