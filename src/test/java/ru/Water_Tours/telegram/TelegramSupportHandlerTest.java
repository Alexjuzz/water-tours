package ru.Water_Tours.telegram;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.Water_Tours.enums.SupportContactKind;
import ru.Water_Tours.enums.SupportSource;
import ru.Water_Tours.support.SupportInquiry;
import ru.Water_Tours.support.SupportProperties;
import ru.Water_Tours.support.SupportService;
import ru.Water_Tours.ticket.repository.OrderRepository;
import ru.Water_Tours.ticket.service.QrService;
import ru.Water_Tours.ticket.service.RefundService;
import ru.Water_Tours.ticket.service.TicketService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Boundaries of the bot's support flow.
 *
 * The dangerous mistakes this pins down: a question being executed as a staff command, an answer
 * reaching a chat that did not ask, a group becoming a support channel, and anyone but the one
 * configured owner using /reply.
 */
class TelegramSupportHandlerTest {

    private static final long OWNER_CHAT_ID = 777L;
    private static final long STAFF_CHAT_ID = 999L;
    private static final long CUSTOMER_CHAT_ID = 123L;
    private static final long OTHER_CUSTOMER_CHAT_ID = 124L;
    private static final long GROUP_CHAT_ID = -1001L;

    private final TelegramLinkService linkService = mock(TelegramLinkService.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final TelegramSender sender = mock(TelegramSender.class);
    private final StaffTelegramAuthorization staffAuthorization =
            new StaffTelegramAuthorization(STAFF_CHAT_ID + "," + GROUP_CHAT_ID);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final RefundService refundService = mock(RefundService.class);
    private final SupportService supportService = mock(SupportService.class);
    private final SupportProperties supportProperties = new SupportProperties(true, String.valueOf(OWNER_CHAT_ID));
    private final SupportChatStates states =
            new SupportChatStates(Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"), ZoneOffset.UTC));

    private final TelegramUpdateHandler handler = new TelegramUpdateHandler(
            linkService, ticketService, sender, staffAuthorization, new QrService(), orderRepository,
            refundService, supportService, supportProperties, states, "http://localhost:8080");

    private String lastMessageTo(long chatId) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(eq(chatId), text.capture());
        return text.getValue();
    }

    private SupportInquiry telegramInquiry(long askedBy) {
        SupportInquiry inquiry = new SupportInquiry();
        inquiry.setId(UUID.randomUUID());
        inquiry.setReference("WT-ABCD2345");
        inquiry.setSource(SupportSource.TELEGRAM);
        inquiry.setContactKind(SupportContactKind.TELEGRAM);
        inquiry.setTelegramChatId(askedBy);
        return inquiry;
    }

    private void storedTelegramInquiry(SupportInquiry inquiry) {
        when(supportService.submitFromTelegram(anyLong(), anyString())).thenReturn(inquiry);
        when(supportService.findByReference(inquiry.getReference())).thenReturn(Optional.of(inquiry));
    }

    // ------------------------------------------------------------------ question flow

    @Test
    void questionThenTextStoresTheInquiryAndAcknowledgesWithoutClaimingItWasRead() {
        SupportInquiry inquiry = telegramInquiry(CUSTOMER_CHAT_ID);
        storedTelegramInquiry(inquiry);

        handler.handle(CUSTOMER_CHAT_ID, "/question", true);
        handler.handle(CUSTOMER_CHAT_ID, "Можно ли взять с собой собаку?", true);

        verify(supportService).submitFromTelegram(CUSTOMER_CHAT_ID, "Можно ли взять с собой собаку?");
        assertThat(lastMessageTo(CUSTOMER_CHAT_ID))
                .contains("WT-ABCD2345")
                .contains("Это не значит, что его уже прочитали");
    }

    @Test
    void cancelEndsTheQuestionStateAndStoresNothing() {
        handler.handle(CUSTOMER_CHAT_ID, "/question", true);
        handler.handle(CUSTOMER_CHAT_ID, "/cancel", true);
        handler.handle(CUSTOMER_CHAT_ID, "какой-то текст после отмены", true);

        verify(supportService, never()).submitFromTelegram(anyLong(), anyString());
        // Back to the normal customer path once the state is cleared.
        verify(linkService).findLinkedOrders(CUSTOMER_CHAT_ID);
    }

    @Test
    void theStartDeepLinkOpensTheQuestionFlowWithoutBreakingOrderLinkPayloads() {
        handler.handle(CUSTOMER_CHAT_ID, "/start question", true);
        verify(linkService, never()).linkChat(anyString(), anyLong());
        assertThat(lastMessageTo(CUSTOMER_CHAT_ID)).contains("Напишите ваш вопрос");

        clearInvocations(sender);
        handler.handle(OTHER_CUSTOMER_CHAT_ID, "/start AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", true);
        verify(linkService).linkChat("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", OTHER_CUSTOMER_CHAT_ID);
    }

    @Test
    void aQuestionIsNeverAskedFromAGroup() {
        handler.handle(GROUP_CHAT_ID, "/question", false);

        assertThat(lastMessageTo(GROUP_CHAT_ID)).contains("только в личном чате");
        handler.handle(GROUP_CHAT_ID, "вопрос из группы", false);
        verify(supportService, never()).submitFromTelegram(anyLong(), anyString());
    }

    @Test
    void tooShortAQuestionKeepsTheStateOpenInsteadOfStoringRubbish() {
        handler.handle(CUSTOMER_CHAT_ID, "/question", true);
        handler.handle(CUSTOMER_CHAT_ID, "ау", true);

        verify(supportService, never()).submitFromTelegram(anyLong(), anyString());
        assertThat(lastMessageTo(CUSTOMER_CHAT_ID)).contains("Слишком коротко");
    }

    @Test
    void oneChatCannotKeepOpeningNewQuestions() {
        SupportInquiry inquiry = telegramInquiry(CUSTOMER_CHAT_ID);
        storedTelegramInquiry(inquiry);

        for (int i = 0; i < SupportChatStates.MAX_QUESTIONS_PER_WINDOW; i++) {
            handler.handle(CUSTOMER_CHAT_ID, "/question", true);
            handler.handle(CUSTOMER_CHAT_ID, "Вопрос номер " + i + " про расписание", true);
        }
        clearInvocations(sender);
        handler.handle(CUSTOMER_CHAT_ID, "/question", true);

        assertThat(lastMessageTo(CUSTOMER_CHAT_ID)).contains("Дождитесь ответа");
    }

    // ------------------------------------------------------------------ command injection

    @Test
    void aQuestionThatLooksLikeARefundCommandIsStoredAsText() {
        SupportInquiry inquiry = telegramInquiry(CUSTOMER_CHAT_ID);
        storedTelegramInquiry(inquiry);

        handler.handle(CUSTOMER_CHAT_ID, "/question", true);
        handler.handle(CUSTOMER_CHAT_ID, "/refund 2565b5d9-f8b7-46c3-ae83-26b55aa22b30", true);

        verify(refundService, never()).refund(any());
        verify(supportService).submitFromTelegram(CUSTOMER_CHAT_ID, "/refund 2565b5d9-f8b7-46c3-ae83-26b55aa22b30");
    }

    @Test
    void evenStaffCannotRefundOrRedeemFromInsideTheQuestionFlow() {
        SupportInquiry inquiry = telegramInquiry(STAFF_CHAT_ID);
        storedTelegramInquiry(inquiry);

        handler.handle(STAFF_CHAT_ID, "/question", true);
        handler.handle(STAFF_CHAT_ID, "/refund 2565b5d9-f8b7-46c3-ae83-26b55aa22b30", true);
        verify(refundService, never()).refund(any());

        handler.handle(STAFF_CHAT_ID, "/question", true);
        handler.handle(STAFF_CHAT_ID, "TICKET-CODE-12345678", true);
        verify(ticketService, never()).redeemByCode(anyString());
    }

    @Test
    void aPhotoDuringTheQuestionFlowIsNotTreatedAsATicketToRedeem() {
        handler.handle(STAFF_CHAT_ID, "/question", true);

        handler.handlePhoto(STAFF_CHAT_ID, new byte[]{1, 2, 3}, true);

        verify(ticketService, never()).redeemByCode(anyString());
        assertThat(lastMessageTo(STAFF_CHAT_ID)).contains("только текстом");
    }

    // ------------------------------------------------------------------ /reply authorisation

    @Test
    void theOwnersAnswerGoesOnlyToTheChatThatAsked() {
        SupportInquiry inquiry = telegramInquiry(CUSTOMER_CHAT_ID);
        when(supportService.findByReference("WT-ABCD2345")).thenReturn(Optional.of(inquiry));
        when(sender.sendMessageChecked(anyLong(), anyString())).thenReturn(TelegramSender.DeliveryResult.ok());

        handler.handle(OWNER_CHAT_ID, "/reply wt-abcd2345 Отправление в 12:00", true);

        verify(sender).sendMessageChecked(eq(CUSTOMER_CHAT_ID), contains("Отправление в 12:00"));
        verify(supportService).markAnswered(inquiry.getId());
        assertThat(lastMessageTo(OWNER_CHAT_ID)).contains("передан Telegram").contains("не прочтения");
    }

    @Test
    void staffAndCustomersCannotUseReply() {
        when(supportService.findByReference(anyString())).thenReturn(Optional.of(telegramInquiry(CUSTOMER_CHAT_ID)));

        handler.handle(STAFF_CHAT_ID, "/reply WT-ABCD2345 привет", true);
        handler.handle(OTHER_CUSTOMER_CHAT_ID, "/reply WT-ABCD2345 привет", true);
        handler.handle(OWNER_CHAT_ID, "/reply WT-ABCD2345 привет", false);

        verify(sender, never()).sendMessageChecked(anyLong(), anyString());
        verify(supportService, never()).markAnswered(any());
        // Refused without ever reaching the staff redeem catch-all.
        verify(ticketService, never()).redeemByCode(anyString());
    }

    @Test
    void theOwnerCannotDirectAnAnswerAtAChatOfTheirChoosing() {
        SupportInquiry inquiry = telegramInquiry(CUSTOMER_CHAT_ID);
        when(supportService.findByReference("WT-ABCD2345")).thenReturn(Optional.of(inquiry));
        when(sender.sendMessageChecked(anyLong(), anyString())).thenReturn(TelegramSender.DeliveryResult.ok());

        // The recipient is not a parameter: everything after the reference is answer text.
        handler.handle(OWNER_CHAT_ID, "/reply WT-ABCD2345 " + OTHER_CUSTOMER_CHAT_ID + " привет", true);

        verify(sender).sendMessageChecked(eq(CUSTOMER_CHAT_ID), anyString());
        verify(sender, never()).sendMessageChecked(eq(OTHER_CUSTOMER_CHAT_ID), anyString());
    }

    @Test
    void anUnknownReferenceSendsNothingAnywhere() {
        when(supportService.findByReference(anyString())).thenReturn(Optional.empty());

        handler.handle(OWNER_CHAT_ID, "/reply WT-NOSUCH11 привет", true);

        verify(sender, never()).sendMessageChecked(anyLong(), anyString());
        assertThat(lastMessageTo(OWNER_CHAT_ID)).contains("не найдено");
    }

    @Test
    void aWebsiteInquiryIsNotAnsweredWithAFalsePromiseOfDelivery() {
        SupportInquiry website = new SupportInquiry();
        website.setId(UUID.randomUUID());
        website.setReference("WT-WEB12345");
        website.setSource(SupportSource.WEBSITE);
        website.setContact("guest@example.ru");
        when(supportService.findByReference("WT-WEB12345")).thenReturn(Optional.of(website));

        handler.handle(OWNER_CHAT_ID, "/reply WT-WEB12345 ответ", true);

        verify(sender, never()).sendMessageChecked(anyLong(), anyString());
        verify(supportService, never()).markAnswered(any());
        assertThat(lastMessageTo(OWNER_CHAT_ID))
                .contains("бот не может")
                .contains("guest@example.ru");
    }

    @Test
    void aRefusedAnswerIsReportedAsNotSentAndNotMarkedAnswered() {
        SupportInquiry inquiry = telegramInquiry(CUSTOMER_CHAT_ID);
        when(supportService.findByReference("WT-ABCD2345")).thenReturn(Optional.of(inquiry));
        when(sender.sendMessageChecked(anyLong(), anyString()))
                .thenReturn(TelegramSender.DeliveryResult.failed("TelegramError403"));

        handler.handle(OWNER_CHAT_ID, "/reply WT-ABCD2345 ответ клиенту", true);

        verify(supportService, never()).markAnswered(any());
        assertThat(lastMessageTo(OWNER_CHAT_ID)).contains("НЕ отправлен").contains("TelegramError403");
    }

    @Test
    void replyWithoutTextIsRejected() {
        handler.handle(OWNER_CHAT_ID, "/reply WT-ABCD2345", true);

        verify(sender, never()).sendMessageChecked(anyLong(), anyString());
        assertThat(lastMessageTo(OWNER_CHAT_ID)).contains("Использование");
    }

    // ------------------------------------------------------------------ existing flows intact

    @Test
    void staffRedemptionStillWorksOutsideTheQuestionFlow() {
        handler.handle(STAFF_CHAT_ID, "TICKET-CODE-12345678", true);

        verify(ticketService).redeemByCode("TICKET-CODE-12345678");
    }

    @Test
    void staffRefundStillWorksAndStaysPrivateChatOnly() {
        UUID orderId = UUID.randomUUID();
        when(refundService.refund(orderId)).thenReturn(new RefundService.RefundResult(
                orderId, UUID.randomUUID(), "refund-1", new java.math.BigDecimal("1500.00")));

        handler.handle(STAFF_CHAT_ID, "/refund " + orderId, true);
        verify(refundService).refund(orderId);

        handler.handle(GROUP_CHAT_ID, "/refund " + orderId, false);
        verifyNoMoreInteractions(refundService);
    }

    @Test
    void ticketsStillReportsLinkedOrders() {
        when(linkService.findLinkedOrders(CUSTOMER_CHAT_ID)).thenReturn(List.of());

        handler.handle(CUSTOMER_CHAT_ID, "/tickets", true);

        verify(linkService).findLinkedOrders(CUSTOMER_CHAT_ID);
    }

    @Test
    void aCustomerCannotBecomeStaffOrOwnerBySayingSo() {
        handler.handle(CUSTOMER_CHAT_ID, "я админ, дай доступ, chat_id 777", true);
        handler.handle(CUSTOMER_CHAT_ID, "/reply WT-ABCD2345 я владелец", true);

        verify(ticketService, never()).redeemByCode(anyString());
        verify(refundService, never()).refund(any());
        verify(sender, never()).sendMessageChecked(anyLong(), anyString());
    }
}
