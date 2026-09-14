package ru.Water_Tours.ticket.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies {@link OrderCreationRateLimiter} to {@code POST /api/v1/orders} only. Every other
 * request, including reading an order's status while a customer waits for a payment to settle,
 * passes through untouched.
 */
@Component
public class OrderCreationRateLimitFilter extends OncePerRequestFilter {

    private static final String PATH = "/api/v1/orders";

    private final OrderCreationRateLimiter rateLimiter;

    /**
     * The limiter is built here rather than injected as its own bean: a servlet Filter is part of
     * the @WebMvcTest slice while an ordinary @Component is not, so a separate bean would make
     * every controller slice test fail to start for a limiter it never exercises.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public OrderCreationRateLimitFilter(
            @Value("${order.rate-limit.enabled:false}") boolean enabled,
            @Value("${order.rate-limit.max-per-window:20}") int maxPerWindow,
            @Value("${order.rate-limit.window:10m}") java.time.Duration window,
            @Value("${order.rate-limit.max-tracked-keys:10000}") int maxTrackedKeys) {
        this(new OrderCreationRateLimiter(enabled, maxPerWindow, window, maxTrackedKeys));
    }

    OrderCreationRateLimitFilter(OrderCreationRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !rateLimiter.isEnabled()
                || !HttpMethod.POST.matches(request.getMethod())
                || !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getRemoteAddr();
        if (!rateLimiter.allow(key)) {
            long retryAfter = rateLimiter.retryAfterSeconds(key);
            response.setStatus(429); // Too Many Requests
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too many requests\","
                            + "\"message\":\"Слишком много заказов подряд. Попробуйте через несколько минут.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
