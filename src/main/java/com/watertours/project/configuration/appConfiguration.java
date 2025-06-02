package com.watertours.project.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class appConfiguration {

    @Bean
    public Duration emailConfirmationCodeTtl(){
        return Duration.ofMinutes(5);
    }
}
