package com.watertours.project.service.pdfService;


import com.watertours.project.model.entity.order.TicketOrder;
import com.watertours.project.model.entity.ticket.QuickTicket;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class PDFCreatorService {

    private final QRCodeService qrCodeService;
    private final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(PDFCreatorService.class);

    @Autowired
    public PDFCreatorService(QRCodeService qrCodeService) {
        this.qrCodeService = qrCodeService;
    }

    public byte[] generateTicketsPdf(TicketOrder order) {
        logger.info("Вызов метода generateTicketsPdf: Генерация PDF для заказа: {}", order.getId());
        try (PDDocument document = new PDDocument()) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            for (QuickTicket ticket : order.getTicketList()) {
                byte[] qr = qrCodeService.generateQRCode(ticket.getUuid().toString());
                addTicketToPdf(document, ticket, order, qr);
            }
            document.save(outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void addTicketToPdf(PDDocument document, QuickTicket ticket, TicketOrder order, byte[] qr) throws IOException {
        PDPage page = new PDPage(PDRectangle.A5);
        document.addPage(page);

        float width = page.getMediaBox().getWidth();
        float height = page.getMediaBox().getHeight();
        float margin = 30;
        float y = height - margin;

        InputStream fontStream = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf");
        PDType0Font font = PDType0Font.load(document, fontStream);

        PDPageContentStream content = new PDPageContentStream(document, page);

        // 1. Шапка — Water-Tours.ru
        String title = "WATER-TOURS.RU";
        content.beginText();
        content.setFont(font, 23);
        float titleWidth = font.getStringWidth(title) / 1000 * 26;
        content.newLineAtOffset((width - titleWidth-40) / 2, y);
        content.showText(title);
        content.endText();

        y -= 40;

        // 2. Основной блок (две колонки)
        float leftColX = margin;
        float rightColX = width - margin - 100; // место под QR

        // Дата покупки
        content.beginText();
        content.setFont(font, 12);
        content.newLineAtOffset(leftColX, y);
        content.showText("Дата покупки: " );
        content.endText();

        // QR-код (правый верхний угол, выровнять по y)
        PDImageXObject pdImage = PDImageXObject.createFromByteArray(document, qr, "QR");
        content.drawImage(pdImage, rightColX, y - 70, 120, 120);

        y -= 30;
        // Имя, телефон, почта (в левой колонке)
        content.beginText();
        content.setFont(font, 12);
        content.newLineAtOffset(leftColX, y);
        content.showText("Имя: " + (order.getBuyerName() != null ? order.getBuyerName() : "-"));
        content.endText();

        y -= 16;
        content.beginText();
        content.setFont(font, 12);
        content.newLineAtOffset(leftColX, y);
        content.showText("Телефон: " );
        content.endText();

        y -= 16;
        content.beginText();
        content.setFont(font, 12);
        content.newLineAtOffset(leftColX, y);
        content.showText("Почта: " + (order.getEmail() != null ? order.getEmail() : "-"));
        content.endText();

        // 3. Маршрут — крупнее, ниже
        y -= 40;
        content.beginText();
        content.setFont(font, 10);
        content.newLineAtOffset(leftColX, y);
        content.showText("Маршрут: Центральный причал — Остров — Центральный причал");
        content.endText();

        // 4. Описание билета (по центру)
        y -= 30;
        String ticketInfo = "Билет на прогулочный корабль №1";
        content.beginText();
        content.setFont(font, 16);
        float ticketInfoWidth = font.getStringWidth(ticketInfo) / 1000 * 16;
        content.newLineAtOffset((width - ticketInfoWidth) / 2, y);
        content.showText(ticketInfo);
        content.endText();

        y -= 25;
        String dateInfo = "Дата и время отправления: " + (ticket.getDateStamp() != null ? ticket.getDateStamp().toString() : "-");
        content.beginText();
        content.setFont(font, 12);
        float dateInfoWidth = font.getStringWidth(dateInfo) / 1000 * 12;
        content.newLineAtOffset((width - dateInfoWidth) / 2, y);
        content.showText(dateInfo);
        content.endText();

        // 5. Подвал (правила)
        float rulesY = margin + 140;
        content.beginText();
        content.setFont(font, 11);
        content.newLineAtOffset(margin, rulesY);
        content.showText("Правила:");
        content.newLineAtOffset(0, -14);
        content.setFont(font, 10);
        content.showText("- Билет действителен до: " );
        content.newLineAtOffset(0, -12);
        content.showText("- На судне запрещено курить.");
        content.newLineAtOffset(0, -12);
        content.showText("- Соблюдайте правила безопасности.");
        content.endText();

        content.close();
    }



}
