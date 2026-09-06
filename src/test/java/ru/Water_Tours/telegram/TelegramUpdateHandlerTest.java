package ru.Water_Tours.telegram;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.ticket.TicketResponse;
import ru.Water_Tours.ticket.service.TicketService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramUpdateHandlerTest {

    private final TelegramLinkService linkService = mock(TelegramLinkService.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final TelegramSender sender = mock(TelegramSender.class);
    private final TelegramUpdateHandler handler =
            new TelegramUpdateHandler(linkService, ticketService, sender, "http://localhost:8080");

    @Test
    void startWithValidPayloadLinksAndConfirmsInRussian() {
        when(linkService.linkChat("payload", 123L)).thenReturn(new Order());

        handler.handle(123L, "/start payload");

        verify(linkService).linkChat("payload", 123L);
        verify(sender).sendMessage(eq(123L), contains("привязан"));
    }

    @Test
    void startWithoutPayloadAsksToOpenLinkFromSite() {
        handler.handle(123L, "/start");

        verifyNoInteractions(linkService);
        verify(sender).sendMessage(eq(123L), contains("ссылку с сайта"));
    }

    @Test
    void startWithInvalidPayloadRepliesWithRejection() {
        when(linkService.linkChat(any(), anyLong())).thenThrow(new IllegalArgumentException("bad payload"));

        handler.handle(123L, "/start bad-payload");

        verify(sender).sendMessage(eq(123L), contains("недействительна"));
    }

    @Test
    void statusForUnlinkedChatAsksToLinkFirst() {
        when(linkService.findLinkedOrder(123L)).thenReturn(Optional.empty());

        handler.handle(123L, "/tickets");

        verify(sender).sendMessage(eq(123L), contains("не привязан"));
        verifyNoInteractions(ticketService);
    }

    @Test
    void statusForLinkedChatSendsTicketSummaryAndPdfLink() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setAccessToken(UUID.randomUUID());
        when(linkService.findLinkedOrder(123L)).thenReturn(Optional.of(order));
        TicketResponse ticket = new TicketResponse(UUID.randomUUID(), "code", "a@a.com",
                Instant.now(), Instant.now(), Instant.now(), TicketType.ADULT, TicketStatus.ISSUED);
        when(ticketService.getTickets(order.getId())).thenReturn(List.of(ticket));

        handler.handle(123L, "hello");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendMessage(eq(123L), captor.capture());
        assertThat(captor.getValue())
                .contains("действителен")
                .contains("http://localhost:8080/api/v1/orders/" + order.getId() + "/tickets/pdf?accessToken=" + order.getAccessToken());
    }
}
