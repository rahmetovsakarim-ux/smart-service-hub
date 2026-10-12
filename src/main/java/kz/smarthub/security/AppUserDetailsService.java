package kz.smarthub.security;

import kz.smarthub.domain.User;
import kz.smarthub.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Телефон НЕМЕСЕ e-mail арқылы кіру (FR-01). Principal аты = телефон нөмірі. */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public AppUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String login) throws UsernameNotFoundException {
        String l = login == null ? "" : login.trim();
        User u = users.findByPhone(l)
                .or(() -> users.findByEmail(l.toLowerCase()))
                .orElseThrow(() -> new UsernameNotFoundException("Пайдаланушы табылмады"));
        return org.springframework.security.core.userdetails.User
                .withUsername(u.getPhone())
                .password(u.getPasswordHash())
                .roles(u.getRole().name())
                .disabled(!u.isActive())
                .build();
    }
}
