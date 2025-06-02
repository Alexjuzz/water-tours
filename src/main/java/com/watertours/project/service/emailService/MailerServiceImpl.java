package com.watertours.project.service.emailService;

import com.watertours.project.interfaces.email.MailerService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;

import static java.nio.charset.StandardCharsets.UTF_8;

@Service
public class MailerServiceImpl implements MailerService {
    private final JavaMailSender  mailSender;

    @Value("${spring.mail.username}")
    private String from;

    public MailerServiceImpl(JavaMailSender  mailerService) {
        this.mailSender = mailerService;
    }

    @Override
    public void sendConfirmationEmail(String to, String code) throws MessagingException {
        String url = "http://localhost:8080/confirm-code?email=" + URLEncoder.encode(to, UTF_8) + "&code=" + code;

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, "UTF_8");
        helper.setTo(to);
        helper.setFrom(from);
        helper.setSubject("Подтвердите свой  Email");
        helper.setText("Для подтверждения Email перейдите по ссылке: <a href=\"" + url + "\">Подтвердить Email</a>", true);
        mailSender.send(message);
    }
}
