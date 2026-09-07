package ru.Water_Tours.telegram;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.Water_Tours.enums.TicketStatus;
import ru.Water_Tours.enums.TicketType;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.model.ticket.TicketResponse;
import ru.Water_Tours.ticket.service.QrService;
import ru.Water_Tours.ticket.service.TicketService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramUpdateHandlerTest {

    private static final long STAFF_CHAT_ID = 999L;
    private static final long CUSTOMER_CHAT_ID = 123L;
    private static final long GROUP_CHAT_ID = -1001L;

    private final TelegramLinkService linkService = mock(TelegramLinkService.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final TelegramSender sender = mock(TelegramSender.class);
    private final StaffTelegramAuthorization staffAuthorization =
            new StaffTelegramAuthorization(STAFF_CHAT_ID + "," + GROUP_CHAT_ID);
    private final QrService qrService = new QrService();
    private final TelegramUpdateHandler handler = new TelegramUpdateHandler(
            linkService, ticketService, sender, staffAuthorization, qrService, "http://localhost:8080");

    private byte[] qrPngBytes(String content) throws Exception {
        BufferedImage image = qrService.generateQRCodeImage(content, 300);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private Order orderWithDate(String createdAt) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setAccessToken(UUID.randomUUID());
        order.setCreatedAt(Instant.parse(createdAt));
        return order;
    }

    @Test
    void startWithValidPayloadLinksAndConfirmsInRussian() {
        when(linkService.linkChat("payload", CUSTOMER_CHAT_ID)).thenReturn(new Order());

        handler.handle(CUSTOMER_CHAT_ID, "/start payload", true);

        verify(linkService).linkChat("payload", CUSTOMER_CHAT_ID);
        verify(sender).sendMessage(eq(CUSTOMER_CHAT_ID), contains("привязан"));
    }

    @Test
    void startWithoutPayloadAsksToOpenLinkFromSite() {
        handler.handle(CUSTOMER_CHAT_ID, "/start", true);

        verifyNoInteractions(linkService);
        verify(sender).sendMessage(eq(CUSTOMER_CHAT_ID), contains("ссылку с сайта"));
    }

    @Test
    void startWithInvalidPayloadRepliesWithRejection() {
        when(linkService.linkChat(any(), anyLong())).thenThrow(new IllegalArgumentException("bad payload"));

        handler.handle(CUSTOMER_CHAT_ID, "/start bad-payload", true);

        verify(sender).sendMessage(eq(CUSTOMER_CHAT_ID), contains("недействительна"));
    }

    @Test
    void statusForUnlinkedChatAsksToLinkFirst() {
        when(linkService.findLinkedOrders(CUSTOMER_CHAT_ID)).thenReturn(List.of());

        handler.handle(CUSTOMER_CHAT_ID, "/tickets", true);

        verify(sender).sendMessage(eq(CUSTOMER_CHAT_ID), contains("не привязан"));
        verifyNoInteractions(ticketService);
    }

    @Test
    void statusForLinkedChatSendsTicketSummaryAndPdfLink() {
        Order order = orderWithDate("2026-01-10T10:00:00Z");
        when(linkService.findLinkedOrders(CUSTOMER_CHAT_ID)).thenReturn(List.of(order));
        TicketResponse ticket = new TicketResponse(UUID.randomUUID(), "code", "a@a.com",
                Instant.now(), Instant.now(), Instant.now(), TicketType.ADULT, TicketStatus.ISSUED);
        when(ticketService.getTickets(order.getId())).thenReturn(List.of(ticket));

        handler.handle(CUSTOMER_CHAT_ID, "hello", true);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendMessage(eq(CUSTOMER_CHAT_ID), captor.capture());
        assertThat(captor.getValue())
                .contains("действителен")
                .contains("http://localhost:8080/api/v1/orders/" + order.getId() + "/tickets/pdf?accessToken=" + order.getAccessToken());
    }

    @Test
    void statusForChatWithMultipleOrdersDescribesEachOne() {
        Order older = orderWithDate("2026-01-01T10:00:00Z");
        Order newer = orderWithDate("2026-01-05T10:00:00Z");
        when(linkService.findLinkedOrders(CUSTOMER_CHAT_ID)).thenReturn(List.of(newer, older));
        TicketResponse newerTicket = new TicketResponse(UUID.randomUUID(), "code-new", "a@a.com",
                Instant.now(), Instant.now(), Instant.now(), TicketType.ADULT, TicketStatus.ISSUED);
        when(ticketService.getTickets(newer.getId())).thenReturn(List.of(newerTicket));
        when(ticketService.getTickets(older.getId())).thenReturn(List.of());

        handler.handle(CUSTOMER_CHAT_ID, "/tickets", true);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendMessage(eq(CUSTOMER_CHAT_ID), captor.capture());
        assertThat(captor.getValue())
                .contains("05.01.2026")
                .contains("01.01.2026")
                .contains("действителен")
                .contains("билеты ещё не выпущены");
    }

    @Test
    void staffChatRedeemsByRawCode() {
        TicketResponse redeemed = new TicketResponse(UUID.randomUUID(), "abc-123", "a@a.com",
                Instant.now(), Instant.now(), Instant.now(), TicketType.ADULT, TicketStatus.USED);
        when(ticketService.redeemByCode("abc-123")).thenReturn(redeemed);

        handler.handle(STAFF_CHAT_ID, "abc-123", true);

        verify(ticketService).redeemByCode("abc-123");
        verify(sender).sendMessage(eq(STAFF_CHAT_ID), contains("Погашён"));
        verifyNoInteractions(linkService);
    }

    @Test
    void staffChatRedeemsByScannedLinkExtractingCode() {
        TicketResponse redeemed = new TicketResponse(UUID.randomUUID(), "abc-123", "a@a.com",
                Instant.now(), Instant.now(), Instant.now(), TicketType.ADULT, TicketStatus.USED);
        when(ticketService.redeemByCode("abc-123")).thenReturn(redeemed);

        handler.handle(STAFF_CHAT_ID, "http://localhost:8080/t/abc-123", true);

        verify(ticketService).redeemByCode("abc-123");
    }

    @Test
    void staffChatOnAlreadyUsedTicketRepliesInRussian() {
        when(ticketService.redeemByCode("abc-123")).thenThrow(new IllegalArgumentException("used"));

        handler.handle(STAFF_CHAT_ID, "abc-123", true);

        verify(sender).sendMessage(eq(STAFF_CHAT_ID), contains("уже был использован"));
    }

    @Test
    void staffChatOnUnknownCodeRepliesNotFound() {
        when(ticketService.redeemByCode("abc-123")).thenThrow(new NoSuchElementException("missing"));

        handler.handle(STAFF_CHAT_ID, "abc-123", true);

        verify(sender).sendMessage(eq(STAFF_CHAT_ID), contains("не найден"));
    }

    @Test
    void staffChatWithUnrecognizableTextAsksForCodeWithoutCallingTicketService() {
        handler.handle(STAFF_CHAT_ID, "привет, как дела?", true);

        verify(sender).sendMessage(eq(STAFF_CHAT_ID), contains("Не удалось распознать"));
        verifyNoInteractions(ticketService);
    }

    @Test
    void nonStaffChatIsUnaffectedByStaffRedeemLogic() {
        when(linkService.findLinkedOrders(CUSTOMER_CHAT_ID)).thenReturn(List.of());

        handler.handle(CUSTOMER_CHAT_ID, "abc-123", true);

        verifyNoInteractions(ticketService);
        verify(sender).sendMessage(eq(CUSTOMER_CHAT_ID), contains("не привязан"));
    }

    @Test
    void staffListedGroupChatCannotRedeemByText() {
        handler.handle(GROUP_CHAT_ID, "abc-123", false);

        verifyNoInteractions(ticketService);
        verify(sender).sendMessage(eq(GROUP_CHAT_ID), contains("личном чате"));
    }

    @Test
    void staffListedGroupChatCannotRedeemByPhoto() throws Exception {
        byte[] photo = qrPngBytes("http://localhost:8080/t/abc-123");

        handler.handlePhoto(GROUP_CHAT_ID, photo, false);

        verifyNoInteractions(ticketService);
        verify(sender).sendMessage(eq(GROUP_CHAT_ID), contains("личном чате"));
    }

    @Test
    void staffChatRedeemsFromPhotoOfQrCode() throws Exception {
        TicketResponse redeemed = new TicketResponse(UUID.randomUUID(), "abc-123", "a@a.com",
                Instant.now(), Instant.now(), Instant.now(), TicketType.ADULT, TicketStatus.USED);
        when(ticketService.redeemByCode("abc-123")).thenReturn(redeemed);
        byte[] photo = qrPngBytes("http://localhost:8080/t/abc-123");

        handler.handlePhoto(STAFF_CHAT_ID, photo, true);

        verify(ticketService).redeemByCode("abc-123");
        verify(sender).sendMessage(eq(STAFF_CHAT_ID), contains("Погашён"));
    }

    @Test
    void nonStaffPhotoIsRejectedWithoutDecoding() {
        handler.handlePhoto(CUSTOMER_CHAT_ID, new byte[]{1, 2, 3}, true);

        verify(sender).sendMessage(eq(CUSTOMER_CHAT_ID), contains("только персонал"));
        verifyNoInteractions(ticketService);
    }

    @Test
    void staffPhotoWithNoDecodableQrRepliesWithFailure() {
        handler.handlePhoto(STAFF_CHAT_ID, new byte[]{1, 2, 3, 4, 5}, true);

        verify(sender).sendMessage(eq(STAFF_CHAT_ID), contains("Не удалось распознать QR"));
        verifyNoInteractions(ticketService);
    }
}
