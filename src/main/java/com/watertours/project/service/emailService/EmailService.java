package com.watertours.project.service.emailService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
    @Autowired
    private JavaMailSender sender;


    public boolean sendConfirmationEmail(String email){
       System.out.println("Отправлено письмо на " + email + " с подтверждением заказа.");
       return true;
   }
}
