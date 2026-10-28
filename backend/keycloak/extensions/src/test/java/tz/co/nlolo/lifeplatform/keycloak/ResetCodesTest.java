package tz.co.nlolo.lifeplatform.keycloak;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResetCodesTest {

    @Test
    void aCodeIsSixDigitsAndOnlyItsHashIsKept() {
        String code = ResetCodes.newCode();
        assertTrue(code.matches("\\d{6}"), code);
        String salt = ResetCodes.newSalt();
        String hash = ResetCodes.hash(salt, code);
        assertFalse(hash.contains(code));
        assertTrue(ResetCodes.matches(salt, hash, code));
    }

    @Test
    void aGuessIsReadAsAPersonTypesItButOnlyTheRightCodeMatches() {
        String salt = ResetCodes.newSalt();
        String hash = ResetCodes.hash(salt, "042917");
        assertTrue(ResetCodes.matches(salt, hash, " 042 917 "));
        assertTrue(ResetCodes.matches(salt, hash, "042-917"));
        assertFalse(ResetCodes.matches(salt, hash, "042918"));
        assertFalse(ResetCodes.matches(salt, hash, "42917"));
        assertFalse(ResetCodes.matches(salt, hash, ""));
        assertFalse(ResetCodes.matches(salt, hash, null));
        // The same code under another salt is another hash: no table of six-digit hashes reads it.
        assertNotEquals(hash, ResetCodes.hash(ResetCodes.newSalt(), "042917"));
    }

    @Test
    void anAccountIsTextedAtMostThreeTimesAnHour() {
        long now = 1_800_000_000L;
        List<Long> recent = ResetCodes.recentSends(ResetCodes.store(List.of(now - 4000, now - 1200, now - 600)), now);
        assertEquals(List.of(now - 1200, now - 600), recent, "an hour-old send no longer counts");
        assertTrue(ResetCodes.maySend(recent));
        assertFalse(ResetCodes.maySend(List.of(now - 900, now - 600, now - 30)));
        assertEquals(List.of(), ResetCodes.recentSends("not,numbers", now));
    }

    @Test
    void theSignatureCoversTheTimeAndTheBody() {
        String a = ResetCodes.sign("secret", 100, "{\"code\":\"1\"}");
        assertEquals(a, ResetCodes.sign("secret", 100, "{\"code\":\"1\"}"));
        assertNotEquals(a, ResetCodes.sign("secret", 101, "{\"code\":\"1\"}"));
        assertNotEquals(a, ResetCodes.sign("secret", 100, "{\"code\":\"2\"}"));
        assertNotEquals(a, ResetCodes.sign("other", 100, "{\"code\":\"1\"}"));
    }
}
