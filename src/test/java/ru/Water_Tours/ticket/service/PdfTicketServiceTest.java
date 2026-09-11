package ru.Water_Tours.ticket.service;

import org.junit.jupiter.api.Test;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import ru.Water_Tours.ticket.repository.TicketRepository;
import ru.Water_Tours.ticket.model.ticket.Ticket;
import ru.Water_Tours.ticket.model.OrderItem.OrderItem;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.enums.BoatRouteType;
import ru.Water_Tours.enums.OrderType;
import ru.Water_Tours.enums.TicketType;
import java.time.Instant;
import java.util.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PdfTicketServiceTest {
    @Test void russianTicketsRenderAndQrDecodes() throws Exception {
        var repository = mock(TicketRepository.class);
        UUID orderId = UUID.randomUUID();
        Ticket t = new Ticket();
        t.setTicketType(TicketType.ADULT);
        t.setCode("0123456789abcdef".repeat(4));
        t.setPurchaseEmail("a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + ".example.test");
        t.setValidFrom(Instant.parse("2026-09-06T10:15:00Z"));
        t.setValidTo(Instant.parse("2026-09-07T10:15:00Z"));
        t.setOrder(orderWithItem(TicketType.ADULT, new java.math.BigDecimal("1500.00")));
        when(repository.findAllByOrderId(orderId)).thenReturn(List.of(t,t));
        byte[] bytes = new PdfTicketService(repository,new QrService()).buildTicketsPdfByOrderId(orderId,"https://water-tours.ru/");
        try (PDDocument doc = PDDocument.load(bytes)) {
            assertEquals(2,doc.getNumberOfPages());
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Электронный билет"));
            assertTrue(text.contains("Взрослый - один проход"));
            assertTrue(plain(text).contains("Стоимость: 1 500 ₽"));
            assertTrue(text.contains("07.09.2026 13:15:00"));
            assertTrue(text.contains("Момент окончания не включён"));
            var rendered = new PDFRenderer(doc).renderImageWithDPI(0,150);
            var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(rendered)));
            assertEquals("https://water-tours.ru/t/" + t.getCode(),new MultiFormatReader().decode(bitmap).getText());
            Path dir = Path.of("target/pdf-preview"); Files.createDirectories(dir);
            Files.write(dir.resolve("ticket.pdf"),bytes);
            ImageIO.write(rendered,"png",dir.resolve("ticket.png").toFile());
        }
    }
    /** Text extraction can hand back either a non-breaking or a plain space; compare on plain. */
    private static String plain(String text) {
        return text.replace(' ', ' ');
    }

    private static Order orderWithItem(TicketType type, java.math.BigDecimal price) {
        Order order = new Order();
        order.setOrderType(OrderType.PASSENGER);
        order.setTotalAmount(price);
        OrderItem item = new OrderItem();
        item.setType(type);
        item.setQuantity(1);
        item.setPrice(price);
        item.setAmountPrice(price);
        order.setOrderItems(List.of(item));
        return order;
    }

    @Test void ticketWithoutAnOrderLineStillRendersInsteadOfFailing() throws Exception {
        var repository = mock(TicketRepository.class);
        UUID orderId = UUID.randomUUID();
        Ticket t = new Ticket();
        t.setTicketType(TicketType.CHILD);
        t.setCode(UUID.randomUUID().toString());
        t.setPurchaseEmail("child@example.com");
        t.setValidFrom(Instant.parse("2026-09-06T10:15:00Z"));
        t.setValidTo(Instant.parse("2026-09-09T10:15:00Z"));
        when(repository.findAllByOrderId(orderId)).thenReturn(List.of(t));

        byte[] bytes = new PdfTicketService(repository, new QrService())
                .buildTicketsPdfByOrderId(orderId, "https://water-tours.ru");

        try (PDDocument doc = PDDocument.load(bytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Детский - один проход"));
            assertTrue(text.contains("Стоимость:"));
        }
    }

    @Test void missingTicketsCannotProducePdf() {
        var repository = mock(TicketRepository.class);
        UUID id = UUID.randomUUID();
        when(repository.findAllByOrderId(id)).thenReturn(List.of());
        assertThrows(NoSuchElementException.class,()->new PdfTicketService(repository,new QrService()).buildTicketsPdfByOrderId(id,"https://water-tours.ru"));
    }
    @Test void privateBoatPdfContainsBookingDetails() throws Exception {
        var repository = mock(TicketRepository.class);
        UUID orderId = UUID.randomUUID();
        Order order = new Order();
        order.setOrderType(OrderType.PRIVATE_BOAT);
        order.setBoatDurationMinutes(90);
        order.setBoatGuestCount(5);
        order.setBoatRouteType(BoatRouteType.CUSTOM);
        order.setBoatRouteNote("Вдоль набережной");
        order.setTotalAmount(new java.math.BigDecimal("9000.00"));
        OrderItem boatItem = new OrderItem();
        boatItem.setType(TicketType.PRIVATE_BOAT);
        boatItem.setQuantity(1);
        boatItem.setPrice(new java.math.BigDecimal("9000.00"));
        boatItem.setAmountPrice(new java.math.BigDecimal("9000.00"));
        order.setOrderItems(List.of(boatItem));
        Ticket ticket = new Ticket();
        ticket.setTicketType(TicketType.PRIVATE_BOAT);
        ticket.setCode(UUID.randomUUID().toString());
        ticket.setPurchaseEmail("boat@example.com");
        ticket.setValidFrom(Instant.parse("2026-09-06T10:15:00Z"));
        ticket.setValidTo(Instant.parse("2026-09-09T10:15:00Z"));
        ticket.setOrder(order);
        when(repository.findAllByOrderId(orderId)).thenReturn(List.of(ticket));

        byte[] bytes = new PdfTicketService(repository, new QrService())
                .buildTicketsPdfByOrderId(orderId, "https://water-tours.ru");

        try (PDDocument doc = PDDocument.load(bytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Аренда катера до 6 гостей"));
            assertTrue(plain(text).contains("Продолжительность: 90 минут — 9 000 ₽"));
            assertTrue(text.contains("Гостей: 5"));
            assertTrue(text.contains("Маршрут: свой маршрут"));
            assertTrue(text.contains("Пожелания: Вдоль набережной"));
        }
    }
}
