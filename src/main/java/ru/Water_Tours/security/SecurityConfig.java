package ru.Water_Tours.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Paths reachable without a staff session. This list is the security default (see
     * {@code anyRequest().authenticated()} below), so a new controller is protected unless
     * somebody deliberately adds it here.
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/v1/orders/**",        // customer order API - authorised by the order access token
            "/api/v1/prices",
            "/api/v1/payments/webhook", // provider callback; re-verified against YooKassa
            "/api/v1/support/**",
            "/api/v1/local-checkout/**", // only mapped under the local-checkout profile
            "/checkout.html",            // idem
            "/login", "/logout", "/error",
            "/actuator/health", "/actuator/health/**"
    };

    @Value("${staff.username}")
    private String staffUsername;

    @Value("${staff.password}")
    private String staffPassword;

    @Value("${staff.remember-me-key}")
    private String rememberMeKey;

    /**
     * Optional extra staff logins, one per person, as {@code username:ROLE:bcryptHash} entries
     * separated by commas or newlines. Empty by default: the single configured account below
     * keeps every privilege it has today, so turning this on never takes access away from
     * anyone. ROLE is {@code STAFF} (redemption, order support, mail queue) or {@code OWNER},
     * which additionally allows refunds and price publishing.
     */
    @Value("${staff.additional-accounts:}")
    private String additionalAccounts;

    @Value("${staff.remember-me-validity-seconds:604800}")
    private int rememberMeValiditySeconds;

    private static final List<String> DEFAULT_ORIGINS =
            List.of("https://water-tours.ru", "https://www.water-tours.ru");

    @Value("${cors.allowed-origins:https://water-tours.ru,https://www.water-tours.ru}")
    private String allowedOrigins;

    /**
     * Off by default. HSTS cannot be taken back from a browser that has already seen it, and a
     * subdomain without TLS would be unreachable for the whole max-age. Turn it on once every
     * *.water-tours.ru host is known to serve HTTPS.
     */
    @Value("${security.hsts.include-subdomains:false}")
    private boolean hstsIncludeSubDomains;

    @Value("${security.hsts.max-age-seconds:31536000}")
    private long hstsMaxAgeSeconds;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, LoginAttemptService loginAttemptService) throws Exception {
        http
                .addFilterBefore(new LoginRateLimitFilter(loginAttemptService, "/login"), UsernamePasswordAuthenticationFilter.class)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers(
                                "/api/v1/orders/**",
                                "/api/v1/payments/webhook",
                                // Public question form, posted cross-origin from the WordPress
                                // site exactly like the order API. It creates no session, reads
                                // nothing about anyone and returns only the new reference, so a
                                // forged post can at worst store one question the owner ignores.
                                "/api/v1/support/**"
                        )
                )
                // Transport hardening. HSTS is written only for requests Tomcat reports as secure,
                // which the RemoteIpValve (server.forward-headers-strategy=native) makes true
                // behind the TLS proxy and false on plain local development - so this is safe to
                // leave on in both. 'unsafe-inline' is required because the staff pages, the
                // /t/{code} check page and Spring's generated login page are built as inline HTML
                // with inline <style>, <script> and onsubmit handlers; removing that is a refactor,
                // not a configuration change. Every form on those pages posts to this same origin.
                .headers(h -> h
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(hstsIncludeSubDomains)
                                .maxAgeInSeconds(hstsMaxAgeSeconds))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; "
                                        + "img-src 'self' data:; "
                                        + "style-src 'self' 'unsafe-inline'; "
                                        + "script-src 'self' 'unsafe-inline'; "
                                        + "connect-src 'self'; "
                                        + "form-action 'self'; "
                                        + "frame-ancestors 'none'; "
                                        + "base-uri 'none'")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Money and published prices are owner-only. The single configured
                        // account holds ROLE_OWNER as well as ROLE_STAFF, so this changes
                        // nothing until additional per-person accounts are configured.
                        .requestMatchers("/staff/refund", "/staff/refund/**").hasRole("OWNER")
                        .requestMatchers("/staff/prices", "/staff/prices/**").hasRole("OWNER")
                        .requestMatchers("/t/**").hasRole("STAFF")
                        .requestMatchers("/staff/**").hasRole("STAFF")
                        .requestMatchers(HttpMethod.POST, "/api/v1/tickets/*/redeem").hasRole("STAFF")
                        .requestMatchers("/actuator/**").hasRole("STAFF")
                        // Fail closed: anything not listed above needs a staff session.
                        .anyRequest().authenticated()
                )
                // false: a saved request still wins, so a staff member who was sent to /login from a
                // deep link lands back on it. Without a default, everyone else landed on "/", which
                // is the public site and shows nothing about what they can do.
                .formLogin(form -> form.defaultSuccessUrl("/staff", false))
                .rememberMe(rm -> rm
                        .key(rememberMeKey)
                        .tokenValiditySeconds(rememberMeValiditySeconds)
                )
                .logout(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // An empty or blank override would deny every cross-origin call, storefront included.
        // Falling back to the real origins fails towards a working site rather than a silent
        // outage nobody connects to a configuration typo.
        List<String> origins = splitList(allowedOrigins);
        configuration.setAllowedOrigins(origins.isEmpty() ? DEFAULT_ORIGINS : origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Content-Type", "Authorization",
                "Idempotency-Key",
                // Proves the caller replaying an Idempotency-Key is the caller that created the
                // order; see OrderCreationService.
                "Idempotency-Secret",
                // Keeps the order access token out of the URL (and out of access logs) for the
                // JSON endpoints.
                "X-Order-Token"));
        configuration.setExposedHeaders(List.of("Location", "Content-Disposition"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public InMemoryUserDetailsManager users(PasswordEncoder encoder) {
        List<UserDetails> accounts = new ArrayList<>();
        // The historical single account. It keeps both roles, so no privilege it has today is
        // withdrawn by the OWNER/STAFF split above.
        accounts.add(User.builder()
                .username(staffUsername)
                .password(encoder.encode(staffPassword))
                .roles("STAFF", "OWNER")
                .build());
        accounts.addAll(parseAdditionalAccounts());
        return new InMemoryUserDetailsManager(accounts);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Parses {@code staff.additional-accounts}. A malformed entry fails startup rather than being
     * skipped silently - a login that quietly does not exist is worse than a boot failure
     * somebody can read.
     */
    private List<UserDetails> parseAdditionalAccounts() {
        List<UserDetails> parsed = new ArrayList<>();
        for (String entry : splitList(additionalAccounts)) {
            String[] parts = entry.split(":", 3);
            if (parts.length != 3 || parts[0].isBlank() || parts[2].isBlank()) {
                throw new IllegalStateException(
                        "staff.additional-accounts entries must look like username:ROLE:bcryptHash");
            }
            String role = parts[1].trim().toUpperCase(Locale.ROOT);
            if (!role.equals("STAFF") && !role.equals("OWNER")) {
                throw new IllegalStateException("staff.additional-accounts role must be STAFF or OWNER");
            }
            String[] roles = role.equals("OWNER") ? new String[]{"STAFF", "OWNER"} : new String[]{"STAFF"};
            parsed.add(User.builder()
                    .username(parts[0].trim())
                    .password(parts[2].trim())
                    .roles(roles)
                    .build());
        }
        return parsed;
    }

    private static List<String> splitList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,\\n]"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
