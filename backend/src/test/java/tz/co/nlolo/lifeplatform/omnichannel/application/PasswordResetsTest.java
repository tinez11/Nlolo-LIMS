package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The password reset at /reset (2026-11-19, Authentik cut-over ticket 07; the rules of Keycloak's ResetCodes): a
 * six-digit code texted for a phone that has an agent or customer login, 15 minutes, five wrong tries, three texts an
 * hour and one a minute per number -- the same for a number with no login, so nothing tells them apart.
 */
class PasswordResetsTest {

    static final String PHONE = "+255713000101";
    static final UUID USER = UUID.randomUUID();
    static final UUID PARTY = UUID.randomUUID();

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T08:00:00Z"));
    private final List<String> texted = new ArrayList<>();
    private final List<UUID> linksAskedFor = new ArrayList<>();
    private Optional<ResetLogins.ResetLogin> login = Optional.of(new ResetLogins.ResetLogin(USER, PARTY));

    private final ResetLogins logins = new ResetLogins() {
        @Override
        public Optional<ResetLogin> find(String phone) {
            return PHONE.equals(phone) ? login : Optional.empty();
        }

        @Override
        public String recoveryLink(UUID userId) {
            linksAskedFor.add(userId);
            return "https://login.example.tz/if/flow/lifeplatform-set-password/?flow_token=abc";
        }
    };
    private final PasswordResets resets = new PasswordResets(logins,
        (party, code, language) -> texted.add(party + " " + code + " " + language), clock);

    /** The code from the last text recorded as "party code language" -- by position: a UUID may hold six digits too. */
    private String lastCode() {
        String code = texted.get(texted.size() - 1).split(" ")[1];
        assertThat(code).matches("\\d{6}");
        return code;
    }

    @Test
    void aLoginsNumberIsTextedACodeAndTheRightCodeOpensItsRecoveryLink() {
        resets.request(" 0713 000 101 ", false);

        assertThat(texted).hasSize(1).first().asString().startsWith(PARTY + " ").endsWith(" en");
        assertThat(resets.check(PHONE, lastCode(), false))
            .isEqualTo("https://login.example.tz/if/flow/lifeplatform-set-password/?flow_token=abc");
        assertThat(linksAskedFor).containsExactly(USER);
    }

    @Test
    void theCodeIsTextedInTheirChosenLanguage() {
        resets.request(PHONE, true);
        assertThat(texted.get(0)).endsWith(" sw");
    }

    @Test
    void aNumberWithNoLoginIsAnsweredTheSameAndTextedNothing() {
        resets.request("+255700000555", false);
        assertThat(texted).isEmpty();
        // A guess there answers exactly as a wrong guess for a real number does.
        assertThatThrownBy(() -> resets.check("+255700000555", "123456", false))
            .isInstanceOf(PasswordResets.Refused.class).hasMessage("That code is not right. 4 tries left.");
    }

    @Test
    void aStaffNumberIsNotAResetLoginSoItIsTreatedAsUnknown() {
        login = Optional.empty(); // the adapter never offers a staff login
        resets.request(PHONE, false);
        assertThat(texted).isEmpty();
    }

    @Test
    void fiveWrongCodesSpendIt() {
        resets.request(PHONE, false);
        String right = lastCode();
        String wrong = right.equals("000000") ? "111111" : "000000";
        for (int left = 4; left >= 1; left--) {
            int l = left;
            assertThatThrownBy(() -> resets.check(PHONE, wrong, false))
                .hasMessage("That code is not right. " + l + (l == 1 ? " try" : " tries") + " left.");
        }
        assertThatThrownBy(() -> resets.check(PHONE, wrong, false))
            .hasMessage("That code is not right, and there are no tries left. Ask for a new code.");
        assertThatThrownBy(() -> resets.check(PHONE, right, false)).hasMessage("That code has expired. Ask for a new code.");
        assertThat(linksAskedFor).isEmpty();
    }

    @Test
    void aCodeLastsFifteenMinutes() {
        resets.request(PHONE, false);
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThatThrownBy(() -> resets.check(PHONE, lastCode(), false)).hasMessage("That code has expired. Ask for a new code.");
    }

    @Test
    void aCodeIsUsedOnce() {
        resets.request(PHONE, false);
        String code = lastCode();
        resets.check(PHONE, code, false);
        assertThatThrownBy(() -> resets.check(PHONE, code, false)).hasMessage("That code has expired. Ask for a new code.");
    }

    @Test
    void oneCodeAMinuteAndThreeAnHourToANumberKnownOrNot() {
        for (String phone : List.of(PHONE, "+255700000555")) {
            resets.request(phone, false);
            assertThatThrownBy(() -> resets.request(phone, false))
                .hasMessage("Wait a minute before asking for a new code.");
            clock.advance(Duration.ofMinutes(2));
            resets.request(phone, false);
            clock.advance(Duration.ofMinutes(2));
            resets.request(phone, false);
            clock.advance(Duration.ofMinutes(2));
            assertThatThrownBy(() -> resets.request(phone, true))
                .hasMessage("Umeomba namba nyingi mno kwa namba hii. Jaribu tena baada ya saa moja.");
            assertThatThrownBy(() -> resets.request(phone, false))
                .hasMessage("Too many codes for this number. Try again in an hour.");
            clock.advance(Duration.ofHours(1));
        }
        assertThat(texted).hasSize(3);
    }

    @Test
    void aNumberThatIsNotAPhoneIsRefusedPlainly() {
        assertThatThrownBy(() -> resets.request("hello", false)).hasMessage("Enter a mobile number, such as 0712 345 678");
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
