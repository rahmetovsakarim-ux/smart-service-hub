package kz.smarthub.security;

import kz.smarthub.domain.User;
import kz.smarthub.exception.ApiException;
import kz.smarthub.repository.UserRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Ағымдағы кірген пайдаланушыны береді. */
@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    public User require() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) {
            throw ApiException.unauthorized();
        }
        return users.findByPhone(a.getName()).orElseThrow(ApiException::unauthorized);
    }
}
