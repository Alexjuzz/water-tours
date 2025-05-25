package com.watertours.project.service.emailService;

import com.watertours.project.model.entity.order.TicketOrder;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;


@Service
public class EmailService {
    private final JavaMailSender mailSender;


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
                    "<p>Ваш заказ успешно подтвержден.<p>" +
                    "<p>Кликните <a href='" + confirmationUrl + "'>здесь</a> для подтверждения.</p>", true);
            mailSender.send(message);
        }  catch (MessagingException e){
            System.err.println("Ошибка отправки письма на " + email + ": " + e.getMessage());
        }

    }

    public void sendTicketsEmail(TicketOrder savedOrder) {

    }
}

