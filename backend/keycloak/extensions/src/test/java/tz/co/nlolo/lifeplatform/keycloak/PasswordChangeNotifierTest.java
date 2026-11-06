package tz.co.nlolo.lifeplatform.keycloak;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PasswordChangeNotifierTest {

    @Test
    void theBodyNamesThePersonAndAStaffMemberSendsNoParty() {
        assertEquals("{\"tenantId\":\"t\",\"userId\":\"u\",\"partyId\":\"p\",\"language\":\"sw\"}",
            PasswordChangeNotifier.body("t", "u", "p", "sw"));
        assertEquals("{\"tenantId\":\"t\",\"userId\":\"u\",\"partyId\":\"\",\"language\":\"en\"}",
            PasswordChangeNotifier.body("t", "u", null, "en"));
    }

    @Test
    void quotesInAValueCannotBreakOutOfTheJson() {
        assertEquals("{\"tenantId\":\"a\\\"b\",\"userId\":\"u\",\"partyId\":\"\",\"language\":\"en\"}",
            PasswordChangeNotifier.body("a\"b", "u", "", "en"));
    }

    @Test
    void unconfiguredItSendsNothing() {
        assertFalse(new PasswordChangeNotifier(null, "s").configured());
        assertFalse(new PasswordChangeNotifier("http://x", " ").configured());
    }
}
