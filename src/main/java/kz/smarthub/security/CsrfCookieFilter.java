package kz.smarthub.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Spring Security 6 creates the CSRF token lazily, so the {@code XSRF-TOKEN} cookie would only appear after something
 * reads the token. Touching it on every request guarantees that the single-page frontend always has a cookie it can
 * echo back in the {@code X-XSRF-TOKEN} header.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            token.getToken(); // renders the cookie into the response
        }
        chain.doFilter(request, response);
    }
}
