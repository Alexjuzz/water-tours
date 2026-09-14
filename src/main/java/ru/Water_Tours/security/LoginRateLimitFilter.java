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
 * Blocks further login attempts from an address after too many recent failures, so brute-forcing
 * a staff account costs a full BCrypt verification only up to the threshold.
 *
 * <p>The key is {@code getRemoteAddr()}, which is the real client only because Tomcat's
 * RemoteIpValve rewrites it from the proxy's {@code X-Forwarded-For}
 * ({@code server.forward-headers-strategy=native}, with an explicit trusted-proxy list). The
 * valve ignores forwarded headers from an untrusted peer, so this key cannot be spoofed by a
 * caller that reaches the backend directly. Turning that strategy off silently returns this to
 * one shared key - see LoginAttemptService for what that used to cost.
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
