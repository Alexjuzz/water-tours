package ru.Water_Tours.component;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class TelegramHttpConfig {

    /**
     * @param apiBase where Telegram lives. Defaults to the real API and is expected to stay there;
     *                it is a property so that an acceptance run can point the whole client at a
     *                local mock and prove the flow without a single message reaching a real chat.
     *                Setting it to anything other than the default in production would send the
     *                bot token to that host, so it belongs in a local profile and nowhere else.
     */
    @Bean("telegramRestClient")
    public RestClient telegramRestClient(@Value("${telegram.bot-token:}") String botToken,
                                         @Value("${telegram.api-base:https://api.telegram.org}") String apiBase) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(35));
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(trimTrailingSlash(apiBase) + "/bot" + botToken)
                .build();
    }

    public static String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
