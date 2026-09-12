package ru.Water_Tours.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Blocks further login attempts from an IP after too many recent failures, so brute-forcing
 * the single staff account costs a full BCrypt verification only up to the threshold.
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final LoginAttemptService loginAttemptService;
    private final RequestMatcher loginRequestMatcher;

    public LoginRateLimitFilter(LoginAttemptService loginAttemptService, String loginProcessingUrl) {
        this.loginAttemptService = loginAttemptService;
        this.loginRequestMatcher = new AntPathRequestMatcher(loginProcessingUrl, HttpMethod.POST.name());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (loginRequestMatcher.matches(request)) {
            String key = request.getRemoteAddr();
            if (loginAttemptService.isBlocked(key)) {
                long retryAfterSeconds = Math.max(1, loginAttemptService.retryAfter(key).toSeconds());
                response.setStatus(429); // Too Many Requests
                response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
