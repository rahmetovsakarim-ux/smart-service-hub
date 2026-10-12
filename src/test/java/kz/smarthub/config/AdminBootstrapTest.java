package kz.smarthub.config;

import kz.smarthub.domain.Role;
import kz.smarthub.domain.User;
import kz.smarthub.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The first administrator comes only from the environment: never a hard-coded default account. */
class AdminBootstrapTest {

    private static final String GOOD_PASSWORD = "a-long-secret-1";

    private UserRepository users;
    private PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("HASHED");
    }

    private AdminBootstrap bootstrap(String phone, String email, String name, String password) {
        return new AdminBootstrap(users, encoder, phone, email, name, password);
    }

    @Test
    void doesNothingWhenNotConfigured() {
        bootstrap("", "", "Administrator", "").run();
        verifyNoInteractions(users);
    }

    @Test
    void refusesAWeakPassword() {
        bootstrap("+77001112233", "", "Admin", "short").run();
        verify(users, never()).save(any(User.class));
    }

    @Test
    void refusesAnInvalidPhone() {
        bootstrap("not-a-phone", "", "Admin", GOOD_PASSWORD).run();
        verify(users, never()).save(any(User.class));
    }

    @Test
    void refusesWhenOnlyOneOfPhoneAndPasswordIsGiven() {
        bootstrap("+77001112233", "", "Admin", "").run();
        bootstrap("", "", "Admin", GOOD_PASSWORD).run();
        verify(users, never()).save(any(User.class));
    }

    @Test
    void doesNotCreateASecondAdministrator() {
        when(users.countByRole(Role.ADMIN)).thenReturn(1L);
        bootstrap("+77001112233", "", "Admin", GOOD_PASSWORD).run();
        verify(users, never()).save(any(User.class));
    }

    @Test
    void doesNotTakeOverAnExistingAccount() {
        when(users.existsByPhone("+77001112233")).thenReturn(true);
        bootstrap("+77001112233", "", "Admin", GOOD_PASSWORD).run();
        verify(users, never()).save(any(User.class));
    }

    @Test
    void createsAnAdministratorWithAHashedPassword() {
        bootstrap("+77001112233", "Boss@Hub.kz", "The Boss", GOOD_PASSWORD).run();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        User admin = saved.getValue();
        assertEquals(Role.ADMIN, admin.getRole());
        assertEquals("+77001112233", admin.getPhone());
        assertEquals("boss@hub.kz", admin.getEmail());
        assertEquals("The Boss", admin.getFullName());
        assertEquals("HASHED", admin.getPasswordHash());
        assertNotEquals(GOOD_PASSWORD, admin.getPasswordHash());
    }
}
