package kz.smarthub.security;

import kz.smarthub.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Brute-force protection: lock after repeated failures, per ip+login and per ip, and unlock after the window. */
class LoginAttemptServiceTest {

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
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

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptService attempts = new LoginAttemptService(clock);

    private void fail(String ip, String login, int times) {
        for (int i = 0; i < times; i++) {
            attempts.recordFailure(ip, login);
        }
    }

    @Test
    void locksTheAccountAfterFiveFailures() {
        fail("1.1.1.1", "a@hub.kz", LoginAttemptService.MAX_PER_ACCOUNT - 1);
        assertDoesNotThrow(() -> attempts.assertAllowed("1.1.1.1", "a@hub.kz"));

        fail("1.1.1.1", "a@hub.kz", 1);
        ApiException e = assertThrows(ApiException.class, () -> attempts.assertAllowed("1.1.1.1", "a@hub.kz"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
    }

    @Test
    void loginNameIsCaseAndWhitespaceInsensitive() {
        fail("1.1.1.1", "  A@Hub.kz ", LoginAttemptService.MAX_PER_ACCOUNT);
        assertThrows(ApiException.class, () -> attempts.assertAllowed("1.1.1.1", "a@hub.kz"));
    }

    @Test
    void anAttackerDoesNotLockTheVictimOutFromAnotherAddress() {
        fail("6.6.6.6", "victim@hub.kz", LoginAttemptService.MAX_PER_ACCOUNT);
        assertThrows(ApiException.class, () -> attempts.assertAllowed("6.6.6.6", "victim@hub.kz"));
        assertDoesNotThrow(() -> attempts.assertAllowed("2.2.2.2", "victim@hub.kz"));
    }

    @Test
    void successfulLoginResetsTheCounter() {
        fail("1.1.1.1", "a@hub.kz", LoginAttemptService.MAX_PER_ACCOUNT - 1);
        attempts.recordSuccess("1.1.1.1", "a@hub.kz");
        fail("1.1.1.1", "a@hub.kz", LoginAttemptService.MAX_PER_ACCOUNT - 1);
        assertDoesNotThrow(() -> attempts.assertAllowed("1.1.1.1", "a@hub.kz"));
    }

    @Test
    void unlocksWhenTheWindowHasPassed() {
        fail("1.1.1.1", "a@hub.kz", LoginAttemptService.MAX_PER_ACCOUNT);
        assertThrows(ApiException.class, () -> attempts.assertAllowed("1.1.1.1", "a@hub.kz"));

        clock.advance(LoginAttemptService.WINDOW.plusSeconds(1));
        assertDoesNotThrow(() -> attempts.assertAllowed("1.1.1.1", "a@hub.kz"));
    }

    @Test
    void passwordSprayingAcrossManyLoginsLocksTheAddress() {
        for (int i = 0; i < LoginAttemptService.MAX_PER_IP; i++) {
            attempts.recordFailure("9.9.9.9", "user" + i + "@hub.kz");
        }
        assertThrows(ApiException.class, () -> attempts.assertAllowed("9.9.9.9", "someone-new@hub.kz"));
        assertDoesNotThrow(() -> attempts.assertAllowed("8.8.8.8", "someone-new@hub.kz"));
    }
}
