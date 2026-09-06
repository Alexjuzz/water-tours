    package ru.Water_Tours.ticket.service;

    import org.springframework.stereotype.Service;
    import ru.Water_Tours.ticket.model.ticket.Ticket;
    import ru.Water_Tours.ticket.repository.TicketRepository;

    import java.util.List;
    import java.util.NoSuchElementException;
    import java.util.UUID;


    import org.apache.pdfbox.pdmodel.PDDocument;
    import org.apache.pdfbox.pdmodel.PDPage;
    import org.apache.pdfbox.pdmodel.PDPageContentStream;
    import org.apache.pdfbox.pdmodel.font.PDType1Font;

    import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
    import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

    import java.io.ByteArrayOutputStream;
    import java.awt.image.BufferedImage;


    //TODO - добавить в PDF информацию о туре на русском языке. Добавить стоимость на каждом билете.
    //TODO - Отредактировать положения текстов на странице. Добавить логотип компании. Добавить водяной знак.
    @Service
    public class PdfTicketService {
        private final TicketRepository ticketRepository;
        private final QrService qrService;

        public PdfTicketService(TicketRepository repository, QrService qrService) {
            this.ticketRepository = repository;
            this.qrService = qrService;
        }

        public byte[] buildTicketsPdfByOrderId(UUID orderId, String baseUrl) {
            List<Ticket> ticketList = ticketRepository.findAllByOrderId(orderId);
            if (ticketList.isEmpty()) {
                throw new NoSuchElementException("Tickets for order with id " + orderId + " not found");
            }
            byte[] pdfBytes = buildTicketsPdf(ticketList, baseUrl);

            return pdfBytes;

        }

        private byte[] buildTicketsPdf(List<Ticket> ticketList, String baseUrl) {
            try (PDDocument document = new PDDocument()) {
                for (Ticket t : ticketList) {
                    PDPage page = new PDPage();
                    document.addPage(page);
                    try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                        //ЗАГОЛОВОК
                        contentStream.beginText();
                        contentStream.setFont(PDType1Font.TIMES_ROMAN, 20);
                        contentStream.newLineAtOffset(50, 750);
                        contentStream.showText("Water Tours Ticket");
                        contentStream.endText();
                        // Тип билета
                        contentStream.beginText();
                        contentStream.setFont(PDType1Font.TIMES_ROMAN, 16);
                        contentStream.newLineAtOffset(50, 700);
                        contentStream.showText("Ticket type: " + t.getTicketType().name());
                        contentStream.endText();

                        // КОД БИЛЕТА
                        contentStream.beginText();
                        contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                        contentStream.newLineAtOffset(50, 660);
                        contentStream.showText("Ticket code: " + t.getCode());
                        contentStream.endText();

                        //ValidFrom и ValidTo
                        contentStream.beginText();
                        contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                        contentStream.newLineAtOffset(50, 630);
                        contentStream.showText("Valid from: " + t.getValidFrom().toString() + " to " + t.getValidTo().toString());
                        contentStream.endText();

                        //Email покупателя
                        contentStream.beginText();
                        contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                        contentStream.newLineAtOffset(50, 580);
                        contentStream.showText("Buyer: " + t.getPurchaseEmail());
                        contentStream.endText();

                        //QR код
                        contentStream.beginText();
                        contentStream.setFont(PDType1Font.TIMES_ROMAN, 10);
                        contentStream.newLineAtOffset(360, 500);
                        contentStream.showText("Scan this QR code before boarding");
                        contentStream.endText();
                        String qrContent = baseUrl + "/t/" + t.getCode();
                        BufferedImage qrImage = qrService.generateQRCodeImage(qrContent, 300);
                        PDImageXObject pdImage = LosslessFactory.createFromImage(document, qrImage);
                        contentStream.drawImage(pdImage, 360, 520, 180, 180);
                    }

                }
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                document.save(outputStream);
                return outputStream.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException("Failed to build tickets PDF", e);
            }
        }


    }
