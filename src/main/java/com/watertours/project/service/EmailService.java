package com.watertours.project.service;

import org.springframework.stereotype.Service;

@Service
public class EmailService {
   public boolean sendConfirmationEmail(String email){
       System.out.println("Отправлено письмо на " + email + " с подтверждением заказа.");
       return true;
   }
}
