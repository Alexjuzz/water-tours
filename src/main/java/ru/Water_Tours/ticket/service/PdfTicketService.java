package ru.Water_Tours.ticket.service;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.springframework.stereotype.Service;
import ru.Water_Tours.ticket.model.ticket.Ticket;
import ru.Water_Tours.ticket.repository.TicketRepository;
import java.io.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class PdfTicketService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.of("Europe/Moscow"));
    private final TicketRepository ticketRepository;
    private final QrService qrService;

    public PdfTicketService(TicketRepository repository, QrService qrService) {
        this.ticketRepository = repository;
        this.qrService = qrService;
    }

    public byte[] buildTicketsPdfByOrderId(UUID orderId, String baseUrl) {
        List<Ticket> tickets = ticketRepository.findAllByOrderId(orderId);
        if (tickets.isEmpty()) throw new NoSuchElementException("Tickets not found");
        try (PDDocument doc = new PDDocument();
             InputStream fontData = Objects.requireNonNull(getClass().getResourceAsStream("/fonts/DejaVuSans.ttf"))) {
            PDType0Font font = PDType0Font.load(doc, fontData);
            for (Ticket t : tickets) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                    c.setNonStrokingColor(14, 45, 65);
                    text(c, font, 26, 48, 738, "WATER TOURS");
                    text(c, font, 18, 48, 704, "Электронный билет");
                    c.setStrokingColor(27, 137, 131);
                    c.moveTo(48, 683); c.lineTo(564, 683); c.stroke();
                    String type = switch (t.getTicketType()) {
                        case ADULT -> "Взрослый";
                        case CHILD -> "Детский";
                        case BENEFIT -> "Льготный";
                    };
                    text(c, font, 16, 48, 652, type + " - один проход");
                    text(c, font, 11, 48, 617, "Действует с: " + TIME.format(t.getValidFrom()));
                    text(c, font, 11, 48, 596, "Действует до: " + TIME.format(t.getValidTo()));
                    text(c, font, 10, 48, 575, "Время московское (UTC+03:00). Момент окончания не включён.");
                    text(c, font, 10, 48, 545, "Код билета:");
                    wrapped(c, font, 10, 48, 526, 516, t.getCode());
                    text(c, font, 10, 48, 484, "Покупатель:");
                    wrapped(c, font, 10, 48, 465, 516, Objects.toString(t.getPurchaseEmail(), ""));
                    String url = baseUrl.replaceAll("/+$", "") + "/t/" + t.getCode();
                    c.drawImage(LosslessFactory.createFromImage(doc, qrService.generateQRCodeImage(url, 600)), 196, 176, 220, 220);
                    text(c, font, 11, 112, 148, "Покажите QR-код сотруднику при посадке.");
                    text(c, font, 10, 48, 112, "После использования повторный проход невозможен.");
                    text(c, font, 10, 48, 92, "Билет не закрепляет конкретный рейс или место.");
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build tickets PDF", e);
        }
    }

    private static void text(PDPageContentStream c, PDType0Font font, float size, float x, float y, String value) throws IOException {
        c.beginText(); c.setFont(font, size); c.newLineAtOffset(x, y); c.showText(value); c.endText();
    }

    private static void wrapped(PDPageContentStream c, PDType0Font font, float size, float x, float y, float width, String value) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int cp : value.codePoints().toArray()) {
            String ch = Character.isISOControl(cp) ? " " : new String(Character.toChars(cp));
            try { font.encode(ch); } catch (IllegalArgumentException e) { ch = "?"; }
            if (font.getStringWidth(line.toString() + ch) * size / 1000 > width) {
                text(c, font, size, x, y, line.toString()); y -= 14; line.setLength(0);
            }
            line.append(ch);
        }
        text(c, font, size, x, y, line.toString());
    }
}
