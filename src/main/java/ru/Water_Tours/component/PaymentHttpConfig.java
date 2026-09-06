package ru.Water_Tours.component;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.time.Duration;

@Configuration
public class PaymentHttpConfig {
    @Bean("yookassaRestClientBuilder")
    public RestClient.Builder yookassaRestClientBuilder() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(35));
        return RestClient.builder().requestFactory(factory);
    }
}