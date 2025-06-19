package com.watertours.project.controller.email;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.watertours.project.model.entity.order.TicketOrder;
import com.watertours.project.interfaces.OrderService.OrderService;
import com.watertours.project.service.emailService.EmailConfirmationServiceImpl;
import com.watertours.project.service.orderService.OrderServiceImpl;
import jakarta.mail.MessagingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;


@Controller
public class EmailController {

    private final EmailConfirmationServiceImpl emailConfirmationService;
    private final OrderService orderService;
    private final int RESEND_EMAIL_LIMIT = 3;
    private final int RESEND_EMAIL_TIMEOUT = 10; // minutes
    private final StringRedisTemplate redisTemplate;
    private final Logger logger = LoggerFactory.getLogger(EmailController.class);

    public EmailController(EmailConfirmationServiceImpl emailConfirmationService,OrderService orderService,StringRedisTemplate redisTemplate) {
        this.emailConfirmationService = emailConfirmationService;
        this.orderService = orderService;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/send-email")
    public String sendEmail(@RequestParam String email,
                            @RequestParam String cartId,
                            Model model) throws MessagingException {

        try {
            logger.info("Вызов метода sendEmail с email: {}, cartId: {}", email, cartId);
            emailConfirmationService.sendConfirmationEmail(email,cartId);
            model.addAttribute("message", "Email отправляется! ");

        }catch (MessagingException e) {
            logger.error("Вызван метод sendEmail, ошибка отправки письма: {}", e.getMessage());
            model.addAttribute("message", "Ошибка отправки письма: " + e.getMessage());
            return "fragments/paymentFragments/errorFragment :: error";
        }
        logger.info("Вызов метода sendEmail: Email отправлен на адрес: {}", email);
        model.addAttribute("cartId", cartId);
        return "fragments/paymentFragments/resultFragment :: result";

    }
    @GetMapping("/resend-email")
    public String resendEmail(@RequestParam String email,
                              @RequestParam String cartId,
                              Model model) throws MessagingException {
        logger.info("Вызов метода resendEmail с email: {}, cartId: {}", email, cartId);
        String attemptKey = "resend_attempt:" + cartId;
        Long attempts = redisTemplate.opsForValue().increment(attemptKey);
        if (attempts != null && attempts == 1) {

            redisTemplate.expire(attemptKey, Duration.ofMinutes(RESEND_EMAIL_TIMEOUT));
        }
        if(attempts != null && attempts > RESEND_EMAIL_LIMIT){
            logger.warn("Вызван метод resendEmail, превышен лимит повторной отправки писем для cartId: {}", cartId);
            model.addAttribute("message", "Превышен лимит повторной отправки писем. Пожалуйста, проверьте почту или обратитесь в службу поддержки.");
            return "fragments/paymentFragments/errorFragment :: error";
        }
        try {
            TicketOrder ticketOrder = null;
            try {
                ticketOrder = orderService.getOrderById(cartId);
                logger.info("Вызван метод resendEmail, заказ найден: {}", ticketOrder);
            } catch (JsonProcessingException e) {
                logger.info("Вызван метод resendEmail, ошибка получения заказа: {}", e.getMessage());
                throw new RuntimeException(e);
            }
            if(ticketOrder == null || !ticketOrder.getEmail().equals(email)){
                logger.warn("Вызван метод resendEmail, заказ не найден или Email не совпадает с заказом для cartId: {}", cartId);
                model.addAttribute("message", "Заказ не найден или Email не совпадает с заказом.");
                return "fragments/paymentFragments/errorFragment :: error";
            }
            logger.info("Вызван метод resendEmail, повторная отправка письма на email: {}", email);
            emailConfirmationService.sendConfirmationEmail(email,cartId);
            model.addAttribute("message", "Повторная отправка письма на почту! ");

        }catch (MessagingException e) {
            logger.error("Вызван метод resendEmail, ошибка отправки письма: {}", e.getMessage());
            model.addAttribute("message", "Ошибка отправки письма: " + e.getMessage());
            return "fragments/paymentFragments/errorFragment :: error";
        }
        logger.info("Вызван метод resendEmail: Повторная отправка письма на адрес: {}", email);
        model.addAttribute("cartId", cartId);
        return "fragments/paymentFragments/resultFragment :: result";
    }

    @GetMapping("/confirm-code")
    public String confirmCode(@RequestParam String email,
                              @RequestParam String code,
                              @RequestParam String cartId,
                              Model model) {
        logger.info("Вызов метода confirmCode с email: {}, code: {}, cartId: {}", email, code, cartId);
        model.addAttribute("email", email);
        if (emailConfirmationService.verifyConfirmationCode(code, cartId)) {
            model.addAttribute("code", code);
            model.addAttribute( "cartId",cartId);
            logger.warn("Вызван метод confirmCode: Код подтверждения верный для email: {}, cartId: {}", email, cartId);
            return "fragments/paymentFragments/paymentFragment :: payment";
        } else {
            logger.warn("Вызван метод confirmCode: Неверный код подтверждения или Email для email: {}, cartId: {}", email, cartId);
            model.addAttribute("message", "Неверный код или Email");
            return "fragments/paymentFragments/errorFragment :: error";
        }
    }


    //    private final EmailService emailService;
//    private final StringRedisTemplate redisTemplate;
//    private final OrderRepository repository;
//
//    @Autowired
//    public EmailController(EmailService emailService, StringRedisTemplate redisTemplate, OrderRepository repository) {
//        this.emailService = emailService;
//        this.redisTemplate = redisTemplate;
//        this.repository = repository;
//
//    }
//
//    @GetMapping("/send-email")
//    public String sendEmail(@RequestParam String email,String cartId, Model model) throws MessagingException {
//        String code = UUID.randomUUID().toString().substring(0, 8);
//        redisTemplate.opsForValue().set(email, code, 5, TimeUnit.MINUTES);
//        try {
//            emailService.sendConfirmationEmail(email, code);
//            model.addAttribute("message", "Email отправляется! ");
//            model.addAttribute("cartId", cartId);
//            return "fragments/paymentFragments/resultFragment :: result";
//        } catch (MessagingException e) {
//            redisTemplate.delete(email);
//            model.addAttribute("Ошибка отправки ссылки для подтверждения " + e.getMessage());
//            return "fragments/paymentFragments/errorFragment :: error";
//        }
//
//    }
//
//    @GetMapping("/confirm-code")
//    public String confirmCode(@RequestParam String email, @RequestParam String code, Model model) {
//        String storeCode = redisTemplate.opsForValue().get(email);
//        if (storeCode != null && storeCode.equals(code)) {
//            redisTemplate.delete(email);
//            model.addAttribute("email", email);
//            return "fragments/paymentFragments/paymentFragment :: payment";
//        } else {
//            model.addAttribute("message", "Неверный код или Email");
//            return "fragments/paymentFragments/errorFragment :: error";
//        }
//    }
//
////    @GetMapping("/payment")
////    public String paymentPage(@RequestParam String email, Model model) {
////        model.addAttribute("email", email);
////
////        return "fragments/paymentFragments/paymentFragment :: payment";
////    }
//
//    @GetMapping("/error")
//    public String errorPage(Model model) {
//        model.addAttribute("message", "Неверный код  или Email");
//        return "fragments/paymentFragments/errorFragment :: error";
//    }
//
//    @PostMapping("/process-payment")
//    public String processPayment(@RequestParam String email,
//                                 @RequestParam String cardNumber,
//                                 @RequestParam String cvv,
//                                 @RequestParam String expiry,
//                                 @RequestParam String cartId,
//                                 Model model) {
//        boolean paymentResult = true; // todo : проверить на тестовом сервисе оплату
//        System.out.println(cardNumber + " " + cvv + " " + expiry);
//        String cartKey = "cardId" + email;
//        if (paymentResult) {
//            Optional<TicketOrder> orderOptional = repository.findByCartId(cartId);
//            if(orderOptional.isPresent()){
//                TicketOrder order = orderOptional.get();
//                order.setStatus(OrderStatus.PAID);
//                repository.save(order);
//            }else{
//                model.addAttribute("message", "Заказ не найден");
//                throw  new IllegalArgumentException("Order not found");
//            }
//            model.addAttribute("message", "Оплата прошла успешно, ваша почта " + email);
//            if (redisTemplate.hasKey(cartKey)) {
//                redisTemplate.delete(cartKey);
//            }
//            return "fragments/paymentFragments/finalizeFragment :: finalize";
//        } else {
//            model.addAttribute("message", "Ошибка оплаты");
//            return "fragments/paymentFragments/errorFragment :: error";
//        }
//
//
//    }
}
