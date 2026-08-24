package tz.co.nlolo.lifeplatform.party.api;

import java.util.UUID;

public class PartyNotFoundException extends RuntimeException {
    public PartyNotFoundException(UUID partyId) {
        super("No party found for id " + partyId);
    }
}
