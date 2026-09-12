package ru.Water_Tours.security;

import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

@Component
public class LoginAttemptListener {

    private final LoginAttemptService loginAttemptService;

    public LoginAttemptListener(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @org.springframework.context.event.EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        remoteAddress(event.getAuthentication().getDetails())
                .ifPresent(loginAttemptService::recordFailure);
    }

    @org.springframework.context.event.EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        remoteAddress(event.getAuthentication().getDetails())
                .ifPresent(loginAttemptService::reset);
    }

    private java.util.Optional<String> remoteAddress(Object details) {
        if (details instanceof WebAuthenticationDetails webDetails) {
            return java.util.Optional.ofNullable(webDetails.getRemoteAddress());
        }
        return java.util.Optional.empty();
    }
}
