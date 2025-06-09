package com.watertours.project.service.emailService;

import com.watertours.project.interfaces.email.EmailConfirmationService;
import com.watertours.project.interfaces.email.MailerService;
import jakarta.mail.MessagingException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

//TODO доделать повторную отправку кода подтверждения.
//TODO Обработать ошибки при отправке письма, например, если почтовый сервер недоступен.

@Service
public class EmailConfirmationServiceImpl implements EmailConfirmationService {

    private final StringRedisTemplate redis;
    private final MailerService mailer;
    private final Duration codeTtl;

    public EmailConfirmationServiceImpl(StringRedisTemplate redis, MailerService mailer, Duration codeTtl) {
        this.redis = redis;
        this.mailer = mailer;
        this.codeTtl = codeTtl;
    }

    @Override
    public void sendConfirmationEmail(String email,String cartId) throws MessagingException {
        String code = UUID.randomUUID().toString().substring(0, 8);
        String key = redisCartId(cartId);
        redis.opsForValue().set(key, code, codeTtl);
        mailer.sendConfirmationEmail(email, code);
    }

    @Override
    public boolean verifyConfirmationCode(String code, String cartId) {
        String key = redisCartId(cartId);
        String storeCartId = redis.opsForValue().get(key);
        if (storeCartId != null && storeCartId.equals(code)) {
            redis.delete(key);
            return true;
        }
        return false;
    }

    private String redisCartId(String cartId) {
        return "cart:" + cartId;
    }
}
