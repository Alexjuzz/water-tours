package com.watertours.project.interfaces.email;

import jakarta.mail.MessagingException;

public interface MailerService {
    void sendConfirmationEmail(String to, String code) throws MessagingException;
}
