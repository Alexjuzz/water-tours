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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${staff.username}")
    private String staffUsername;

    @Value("${staff.password}")
    private String staffPassword;

    @Value("${staff.remember-me-key}")
    private String rememberMeKey;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers(
                                "/api/v1/orders/**",
                                "/api/v1/payments/webhook"
                        )
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/t/**").hasRole("STAFF")
                        .requestMatchers(HttpMethod.POST, "/api/v1/tickets/*/redeem").hasRole("STAFF")
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("STAFF") // остальные actuator только staff
                        .anyRequest().permitAll()
                )
                .formLogin(Customizer.withDefaults())
                .rememberMe(rm -> rm
                        .key(rememberMeKey)
                        .tokenValiditySeconds(7 * 24 * 60 * 60)
                )
                .logout(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(
                "http://water-tours.local",
                "https://water-tours.local",
                "http://localhost",
                "http://localhost:3000",
                "http://localhost:8080"
        ));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Authorization", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("Location", "Content-Disposition"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public InMemoryUserDetailsManager users(PasswordEncoder encoder) {
        UserDetails staff = User.builder()
                .username(staffUsername)
                .password(encoder.encode(staffPassword))
                .roles("STAFF")
                .build();

        return new InMemoryUserDetailsManager(staff);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}