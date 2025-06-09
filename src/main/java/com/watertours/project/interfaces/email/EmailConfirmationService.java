package com.watertours.project.interfaces.email;

import jakarta.mail.MessagingException;

public interface EmailConfirmationService {

    void sendConfirmationEmail(String email,String cartId) throws MessagingException;

    boolean verifyConfirmationCode(String code,String cartId) ;

}
