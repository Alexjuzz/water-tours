package ru.Water_Tours.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TelegramPollingJobTest {

    @Test
    void pollDispatchesMessagesAndAdvancesOffsetAcrossCalls() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.telegram.org/bottest-token");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        TelegramUpdateHandler handler = mock(TelegramUpdateHandler.class);
        TelegramPollingJob job = new TelegramPollingJob(client, handler, "test-token");

        server.expect(requestTo("https://api.telegram.org/bottest-token/getUpdates?timeout=25&offset=0"))
                .andExpect(queryParam("offset", "0"))
                .andRespond(withSuccess("""
                        {"ok":true,"result":[{"update_id":42,"message":{"chat":{"id":123},"text":"/tickets"}}]}
                        """, MediaType.APPLICATION_JSON));

        job.poll();

        verify(handler).handle(123L, "/tickets");
        server.verify();

        server.reset();
        server.expect(requestTo("https://api.telegram.org/bottest-token/getUpdates?timeout=25&offset=43"))
                .andExpect(queryParam("offset", "43"))
                .andRespond(withSuccess("{\"ok\":true,\"result\":[]}", MediaType.APPLICATION_JSON));

        job.poll();

        server.verify();
    }

    @Test
    void pollDoesNothingWithoutConfiguredToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.telegram.org/bot");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        TelegramUpdateHandler handler = mock(TelegramUpdateHandler.class);
        TelegramPollingJob job = new TelegramPollingJob(client, handler, "");

        job.poll();

        server.verify();
    }
}
