package ru.Water_Tours.ticket.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.Water_Tours.enums.OrderStatus;
import ru.Water_Tours.ticket.model.order.Order;
import ru.Water_Tours.ticket.repository.OrderRepository;

import java.time.Instant;
import java.util.UUID;
@Service
public class TicketEmailService {
    private final JavaMailSender mailSender;
    private final OrderRepository orderRepository;
    private final PdfTicketService pdfTicketService;
    private final String baseUrl;
    private final String mailFrom;

    public TicketEmailService( JavaMailSender mailSender,
                               OrderRepository orderRepository,
                               PdfTicketService pdfTicketService,
                               @Value("${app.base-url}") String baseUrl,
                               @Value("${app.mail-from}") String mailFrom) {
        this.orderRepository = orderRepository;
        this.pdfTicketService = pdfTicketService;
        this.baseUrl = baseUrl;
        this.mailSender = mailSender;
        this.mailFrom = mailFrom;
    }

    @Transactional
    public void sendTicketsPdf(UUID orderId){
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new IllegalArgumentException("Order with id " + orderId + " not found"));
        if(order.getStatus() != OrderStatus.PAID){
            throw  new IllegalStateException("Order with id " + orderId + " is not paid. Current status: " + order.getStatus());
        }
        if(order.getTicketIssuedAt() == null){
            throw new IllegalStateException("Tickets for order with id " + orderId + " are not issued");
        }
        if(order.getEmail() == null || order.getEmail().isBlank()){
            throw new IllegalStateException("Order with id " + orderId + " does not have a valid email address");
        }
        if(order.getTicketsEmailedAt() != null){
//            throw new IllegalStateException("Tickets for order with id " + orderId + " have already been emailed at " + order.getTicketsEmailedAt());
            return;
        }
        byte[] pdfBytes = pdfTicketService.buildTicketsPdfByOrderId(orderId, baseUrl);

        sendEmailWithPdf(order.getEmail(), orderId, pdfBytes);
        order.setTicketsEmailedAt(Instant.now());
        orderRepository.save(order);
    }
    private void sendEmailWithPdf(String to, UUID orderId, byte[] pdfBytes){
        try{

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(to);
            helper.setSubject("Ваши билеты Water Tours — оплата подтверждена");
            helper.setText("""
                    Здравствуйте!

                    Спасибо за покупку билетов Water Tours — оплата прошла успешно.

                    Ваши билеты во вложении (PDF с QR-кодом на каждый билет). Срок действия
                    и время указаны прямо на билете. Перед посадкой покажите QR-код персоналу
                    для прохода — билет одноразовый.

                    Если у вас возникнут вопросы по заказу, просто ответьте на это письмо.

                    Хорошей прогулки!
                    Water Tours
                    """);
            helper.addAttachment("tickets-" + orderId  + ".pdf", new ByteArrayResource(pdfBytes));
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send email with tickets for order " + orderId, e);
        }
    }
}
