package kz.smarthub.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import kz.smarthub.security.CurrentUser;
import kz.smarthub.security.LoginAttemptService;
import kz.smarthub.service.AuthService;
import kz.smarthub.web.dto.Dto.LoginRequest;
import kz.smarthub.web.dto.Dto.RegisterRequest;
import kz.smarthub.web.dto.Dto.UserDto;
import kz.smarthub.web.dto.Mapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** UC-01 Тіркелу, UC-02 Жүйеге кіру. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthenticationManager authenticationManager;
    private final CurrentUser currentUser;
    private final LoginAttemptService loginAttempts;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AuthService authService, AuthenticationManager authenticationManager,
                          CurrentUser currentUser, LoginAttemptService loginAttempts) {
        this.authService = authService;
        this.authenticationManager = authenticationManager;
        this.currentUser = currentUser;
        this.loginAttempts = loginAttempts;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto register(@Valid @RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    @PostMapping("/login")
    public UserDto login(@Valid @RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        String login = req.login().trim();
        String ip = request.getRemoteAddr();
        loginAttempts.assertAllowed(ip, login);
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(login, req.password()));
        } catch (BadCredentialsException e) {
            loginAttempts.recordFailure(ip, login);
            throw e;
        }
        loginAttempts.recordSuccess(ip, login);
        // Session fixation protection: the login is done by hand (not by a Spring Security filter), so the session id
        // must be rotated here; otherwise an id planted before login would stay valid afterwards.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return Mapper.user(currentUser.require());
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    public UserDto me() {
        return Mapper.user(currentUser.require());
    }
}
