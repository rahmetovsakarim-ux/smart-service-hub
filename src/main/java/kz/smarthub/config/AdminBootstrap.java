package kz.smarthub.config;

import kz.smarthub.domain.Role;
import kz.smarthub.domain.User;
import kz.smarthub.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first administrator on a fresh production database from environment variables, so no default
 * credentials ever have to exist in the code or the repository:
 * <pre>APP_ADMIN_PHONE, APP_ADMIN_EMAIL (optional), APP_ADMIN_NAME (optional), APP_ADMIN_PASSWORD</pre>
 * Does nothing unless phone and password are set, an administrator does not exist yet, and the password is at least
 * {@value #MIN_PASSWORD_LENGTH} characters. Remove the variables after the first start.
 */
@Component
@org.springframework.core.annotation.Order(2)
public class AdminBootstrap implements CommandLineRunner {

    static final int MIN_PASSWORD_LENGTH = 10;
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String phone;
    private final String email;
    private final String name;
    private final String password;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder,
                          @Value("${app.admin.phone:}") String phone,
                          @Value("${app.admin.email:}") String email,
                          @Value("${app.admin.name:Administrator}") String name,
                          @Value("${app.admin.password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.phone = phone.trim();
        this.email = email.trim().toLowerCase();
        this.name = name.isBlank() ? "Administrator" : name.trim();
        this.password = password;
    }

    @Override
    public void run(String... args) {
        if (phone.isEmpty() && password.isEmpty()) {
            return; // not configured: the normal case
        }
        if (phone.isEmpty() || password.isEmpty()) {
            log.warn("Admin bootstrap skipped: both APP_ADMIN_PHONE and APP_ADMIN_PASSWORD must be set");
            return;
        }
        if (!phone.matches("^\\+?[0-9]{10,15}$")) {
            log.warn("Admin bootstrap skipped: APP_ADMIN_PHONE must be 10-15 digits, optionally starting with +");
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            log.warn("Admin bootstrap skipped: APP_ADMIN_PASSWORD must be at least {} characters", MIN_PASSWORD_LENGTH);
            return;
        }
        if (users.countByRole(Role.ADMIN) > 0) {
            log.info("Admin bootstrap skipped: an administrator already exists");
            return;
        }
        if (users.existsByPhone(phone) || (!email.isEmpty() && users.existsByEmail(email))) {
            log.warn("Admin bootstrap skipped: the phone or e-mail is already used by another account");
            return;
        }
        User admin = new User();
        admin.setFullName(name);
        admin.setPhone(phone);
        admin.setEmail(email.isEmpty() ? null : email);
        admin.setPasswordHash(encoder.encode(password));
        admin.setRole(Role.ADMIN);
        users.save(admin);
        log.info("Initial administrator created ({}). Remove APP_ADMIN_* from the environment now.", phone);
    }
}
