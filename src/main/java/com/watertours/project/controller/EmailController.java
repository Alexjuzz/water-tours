package com.watertours.project.controller;

import com.watertours.project.service.emailService.EmailService;
import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.Banner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Controller
public class EmailController {

    private final EmailService emailService;
    private  final StringRedisTemplate redisTemplate;
    @Autowired
    public EmailController(EmailService emailService, StringRedisTemplate redisTemplate) {
        this.emailService = emailService;
        this.redisTemplate = redisTemplate;
    }

    @PostMapping("/send-email")
    public String sendEmail(@RequestParam String email, Model model) throws MessagingException {
            String code = UUID.randomUUID().toString().substring(0,8);
            redisTemplate.opsForValue().set(email,code,10, TimeUnit.MINUTES);
            try {
                emailService.sendConfirmationEmail(email,code);
                model.addAttribute("message","Email отправляется!");
                return "result";
            }catch (MessagingException e){
                redisTemplate.delete(email);
                model.addAttribute("Ошибка отправки ссылки для подтверждения "  +  e.getMessage());
                return "result";
            }

    }

    @GetMapping("/confirm-code")
    public String confirmCode(@RequestParam String email, @RequestParam String code) {
                String storeCode = redisTemplate.opsForValue().get(email);
                if(storeCode != null && storeCode.equals(storeCode)){
                    redisTemplate.delete(email);
                    return "redirect:/payment?email="+email;
                }else {
                    return "redirect:/error";
                }
    }

    @GetMapping("/payment")
    public String paymentPage(@RequestParam String email, Model model){
        model.addAttribute("email", email);
        return "payment";
    }

    @GetMapping("/error")
    public String errorPage(Model model){
        model.addAttribute("message", "Неверный код  или Email");
        return "error";
    }
}
