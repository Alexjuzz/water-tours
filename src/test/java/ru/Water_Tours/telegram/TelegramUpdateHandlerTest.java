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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramUpdateHandlerTest {

    private static final long STAFF_CHAT_ID = 999L;
    private static final long CUSTOMER_CHAT_ID = 123L;

    private final TelegramLinkService linkService = mock(TelegramLinkService.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final TelegramSender sender = mock(TelegramSender.class);
    private final StaffTelegramAuthorization staffAuthorization =
            new StaffTelegramAuthorization(String.valueOf(STAFF_CHAT_ID));
    private final TelegramUpdateHandler handler =
            new TelegramUpdateHandler(linkService, ticketService, sender, staffAuthorization, "http://localhost:8080");

    @Test
    void startWithValidPayloadLinksAndConfirmsInRussian() {
        when(linkService.linkChat("payload", CUSTOMER_CHAT_ID)).thenReturn(new Order());

        handler.handle(CUSTOMER_CHAT_ID, "/start payload");

        verify(linkService).linkChat("payload", CUSTOMER_CHAT_ID);
        verify(sender).sendMessage(eq(CUSTOMER_CHAT_ID), contains("привязан"));
    }

    @Test
    void startWithoutPayloadAsksToOpenLinkFromSite() {
        handler.handle(CUSTOMER_CHAT_ID, "/start");

        verifyNoInteractions(linkService);
        verify(sender).sendMessage(eq(CUSTOMER_CHAT_ID), contains("ссылку с сайта"));
    }

    @Test
    void startWithInvalidPayloadRepliesWithRejection() {
        when(linkService.linkChat(any(), anyLong())).thenThrow(new IllegalArgumentException("bad payload"));

        handler.handle(CUSTOMER_CHAT_ID, "/start bad-payload");

        verify(sender).sendMessage(eq(CUSTOMER_CHAT_ID), contains("недействительна"));
    }

    @Test
    void statusForUnlinkedChatAsksToLinkFirst() {
        when(linkService.findLinkedOrder(CUSTOMER_CHAT_ID)).thenReturn(Optional.empty());

        handler.handle(CUSTOMER_CHAT_ID, "/tickets");

        verify(sender).sendMessage(eq(CUSTOMER_CHAT_ID), contains("не привязан"));
        verifyNoInteractions(ticketService);
    }

    @Test
    void statusForLinkedChatSendsTicketSummaryAndPdfLink() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setAccessToken(UUID.randomUUID());
        when(linkService.findLinkedOrder(CUSTOMER_CHAT_ID)).thenReturn(Optional.of(order));
        TicketResponse ticket = new TicketResponse(UUID.randomUUID(), "code", "a@a.com",
                Instant.now(), Instant.now(), Instant.now(), TicketType.ADULT, TicketStatus.ISSUED);
        when(ticketService.getTickets(order.getId())).thenReturn(List.of(ticket));

        handler.handle(CUSTOMER_CHAT_ID, "hello");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendMessage(eq(CUSTOMER_CHAT_ID), captor.capture());
        assertThat(captor.getValue())
                .contains("действителен")
                .contains("http://localhost:8080/api/v1/orders/" + order.getId() + "/tickets/pdf?accessToken=" + order.getAccessToken());
    }

    @Test
    void staffChatRedeemsByRawCode() {
        TicketResponse redeemed = new TicketResponse(UUID.randomUUID(), "abc-123", "a@a.com",
                Instant.now(), Instant.now(), Instant.now(), TicketType.ADULT, TicketStatus.USED);
        when(ticketService.redeemByCode("abc-123")).thenReturn(redeemed);

        handler.handle(STAFF_CHAT_ID, "abc-123");

        verify(ticketService).redeemByCode("abc-123");
        verify(sender).sendMessage(eq(STAFF_CHAT_ID), contains("Погашён"));
        verifyNoInteractions(linkService);
    }

    @Test
    void staffChatRedeemsByScannedLinkExtractingCode() {
        TicketResponse redeemed = new TicketResponse(UUID.randomUUID(), "abc-123", "a@a.com",
                Instant.now(), Instant.now(), Instant.now(), TicketType.ADULT, TicketStatus.USED);
        when(ticketService.redeemByCode("abc-123")).thenReturn(redeemed);

        handler.handle(STAFF_CHAT_ID, "http://localhost:8080/t/abc-123");

        verify(ticketService).redeemByCode("abc-123");
    }

    @Test
    void staffChatOnAlreadyUsedTicketRepliesInRussian() {
        when(ticketService.redeemByCode("abc-123")).thenThrow(new IllegalArgumentException("used"));

        handler.handle(STAFF_CHAT_ID, "abc-123");

        verify(sender).sendMessage(eq(STAFF_CHAT_ID), contains("уже был использован"));
    }

    @Test
    void staffChatOnUnknownCodeRepliesNotFound() {
        when(ticketService.redeemByCode("abc-123")).thenThrow(new NoSuchElementException("missing"));

        handler.handle(STAFF_CHAT_ID, "abc-123");

        verify(sender).sendMessage(eq(STAFF_CHAT_ID), contains("не найден"));
    }

    @Test
    void staffChatWithUnrecognizableTextAsksForCodeWithoutCallingTicketService() {
        handler.handle(STAFF_CHAT_ID, "привет, как дела?");

        verify(sender).sendMessage(eq(STAFF_CHAT_ID), contains("Не удалось распознать"));
        verifyNoInteractions(ticketService);
    }

    @Test
    void nonStaffChatIsUnaffectedByStaffRedeemLogic() {
        when(linkService.findLinkedOrder(CUSTOMER_CHAT_ID)).thenReturn(Optional.empty());

        handler.handle(CUSTOMER_CHAT_ID, "abc-123");

        verifyNoInteractions(ticketService);
        verify(sender).sendMessage(eq(CUSTOMER_CHAT_ID), contains("не привязан"));
    }
}
