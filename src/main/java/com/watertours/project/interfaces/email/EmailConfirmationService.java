package com.watertours.project.interfaces.email;

import jakarta.mail.MessagingException;

public interface EmailConfirmationService {

    void sendConfirmationEmail(String token) throws MessagingException;

    boolean verifyConfirmationCode(String email,String code);

}
