package kz.smarthub.security;

import kz.smarthub.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection for the login endpoint (in memory, per application instance).
 *
 * <p>Two counters are kept inside a sliding window:
 * <ul>
 *   <li>{@code ip + login}: {@value #MAX_PER_ACCOUNT} failures lock that pair, so a victim is not locked out of their
 *       own account by an attacker on another network;</li>
 *   <li>{@code ip}: {@value #MAX_PER_IP} failures lock the address, which stops password spraying across many logins.</li>
 * </ul>
 * A successful login clears the pair counter. For a multi-instance deployment move the counters into a shared store
 * (e.g. Redis); behind a reverse proxy configure {@code server.forward-headers-strategy} so the real client address
 * is seen.
 */
@Component
public class LoginAttemptService {

    static final int MAX_PER_ACCOUNT = 5;
    static final int MAX_PER_IP = 20;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Counter(int failures, Instant windowStart) {
    }

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptService() {
        this(Clock.systemUTC());
    }

    LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    /** Throws 429 if this client is currently locked out. Call before checking the password. */
    public void assertAllowed(String ip, String login) {
        if (isLocked(accountKey(ip, login), MAX_PER_ACCOUNT) || isLocked(ipKey(ip), MAX_PER_IP)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Тым көп сәтсіз әрекет. 15 минуттан кейін қайталаңыз||Слишком много неудачных попыток. Повторите через 15 минут");
        }
    }

    public void recordFailure(String ip, String login) {
        bump(accountKey(ip, login));
        bump(ipKey(ip));
        if (counters.size() > CLEANUP_THRESHOLD) {
            Instant now = clock.instant();
            counters.entrySet().removeIf(e -> expired(e.getValue(), now));
        }
    }

    public void recordSuccess(String ip, String login) {
        counters.remove(accountKey(ip, login));
    }

    private boolean isLocked(String key, int max) {
        Counter c = counters.get(key);
        return c != null && !expired(c, clock.instant()) && c.failures() >= max;
    }

    private void bump(String key) {
        Instant now = clock.instant();
        counters.merge(key, new Counter(1, now), (old, ignored) ->
                expired(old, now) ? new Counter(1, now) : new Counter(old.failures() + 1, old.windowStart()));
    }

    private static boolean expired(Counter c, Instant now) {
        return c.windowStart().plus(WINDOW).isBefore(now);
    }

    private static String accountKey(String ip, String login) {
        return "a|" + ip + "|" + (login == null ? "" : login.trim().toLowerCase());
    }

    private static String ipKey(String ip) {
        return "i|" + ip;
    }
}
