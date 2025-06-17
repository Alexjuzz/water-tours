package com.watertours.project.service.emailService;

import com.watertours.project.interfaces.email.TicketEmailService;
import com.watertours.project.model.entity.order.TicketOrder;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;


@Service
public class EmailService  implements TicketEmailService {
    @Value("${spring.mail.username}")
    private String from;


    private final JavaMailSender mailSender;
    private final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(EmailService.class);


    @Autowired
    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public boolean sendConfirmationEmail1(String email) {
        System.out.println("Отправлено письмо на " + email + " с подтверждением заказа.");
        return true;
    }

    @Async
    public void sendConfirmationEmail(String email, String code)  throws  MessagingException{
        try {
            String confirmationUrl = "http://localhost:8087/confirm-code?email=" + email + "&code=" + code;


            MimeMessage message = mailSender.createMimeMessage();

            MimeMessageHelper messageHelper = new MimeMessageHelper(message, true, "UTF-8");
            messageHelper.setTo(email);
            messageHelper.setSubject("Подтверждение почты");
            messageHelper.setFrom("juzzleee@yandex.ru");
            messageHelper.setText("<h1> Спасибо за ваш заказ!<h1>" +
                    "<p>Кликните <a href='" + confirmationUrl + "'>здесь</a> для подтверждения.</p>", true);
            mailSender.send(message);
        }  catch (MessagingException e){
            System.err.println("Ошибка отправки письма на " + email + ": " + e.getMessage());
        }

    }

    @Async
    public boolean sendTicketsEmail(TicketOrder savedOrder) {
        if (!savedOrder.isEmailSent() && savedOrder.getEmailRetryCount() <= 3) { //TODO вынести логику в один метод
            try {

                String emailContent = generateTicketEmailContent(savedOrder);

                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper messageHelper = new MimeMessageHelper(message, true, "UTF-8");
                messageHelper.setTo(savedOrder.getEmail());
                messageHelper.setFrom(from);
                messageHelper.setSubject("Ваш заказ билетов на водные туры");
                messageHelper.setText(emailContent, true);


                savedOrder.setEmailSent(true);
                savedOrder.setEmailRetryCount(0); // сбрасываем, если всё ок
                return true;
            } catch (Exception e) {
                // увеличиваем счётчик ошибок
                savedOrder.setEmailRetryCount(savedOrder.getEmailRetryCount() + 1);
                logger.error("Не удалось отправить письмо с билетами по заказу {}: {}", savedOrder.getCartId(), e.getMessage());
                return false;
            }
        }
        return false; // если письмо уже отправлено или превышен лимит попыток
    }

    private String generateTicketEmailContent(TicketOrder order) throws MessagingException{
        StringBuilder content = new StringBuilder();
        content.append("<h1>Ваш заказ успешно оплачен!</h1>");
        content.append("<p>Спасибо за покупку билетов на водные туры!</p>");
        content.append("<h2>Детали заказа:</h2>");
        content.append("<ul>");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        order.getTicketList().forEach(ticket -> {
            content.append("<li>");
            content.append("Тур: ").append(ticket.getType()).append(", ");
            content.append("Дата: ").append(ticket.getDateStamp().format(formatter)).append(", ");
            content.append("</li>");
        });
        return content.toString();
    }


    @Override
    public boolean resendTicketEmail(TicketOrder order) throws Exception {
        return sendTicketsEmail(order);
    }

    @Override
    public void retrySendTicketEmail(TicketOrder order) throws Exception {

    }
}

