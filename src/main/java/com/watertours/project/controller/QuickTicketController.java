package com.watertours.project.controller;

import com.watertours.project.enums.TicketType;
import com.watertours.project.model.dto.BasketUpdateDto;
import com.watertours.project.model.dto.QuickTicketModalDto;
import com.watertours.project.model.dto.TicketUpdateDto;
import com.watertours.project.model.dto.UserDto;
import com.watertours.project.model.entity.order.TicketOrder;
import com.watertours.project.service.EmailService;
import com.watertours.project.service.OrderService;
import com.watertours.project.service.QuickTicketService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@Controller
public class QuickTicketController {

    private final Logger logger = LoggerFactory.getLogger(QuickTicketController.class);

    @Autowired
    private final QuickTicketService quickTicketService;
    private final OrderService orderService;
    private final EmailService emailService;

    public QuickTicketController(QuickTicketService quickTicketService, OrderService orderService, EmailService emailService) {
        this.quickTicketService = quickTicketService;
        this.orderService = orderService;
        this.emailService = emailService;
    }

    @GetMapping("/")
    public String home() {
        return "index";
    }

    @GetMapping("/getModal")
    public String getModal(@RequestParam(required = false) String cartId, Model model) {
        if (cartId == null) {
            cartId = UUID.randomUUID().toString();
        }
        TicketOrder order = orderService.getOrder(cartId);
        QuickTicketModalDto dto = quickTicketService.prepareModalDto(order);
        model.addAttribute("CHILDCount", dto.getChildCount());
        model.addAttribute("SENIORCount", dto.getSeniorCount());
        model.addAttribute("DISCOUNTCount", dto.getDiscountCount());
        model.addAttribute("CHILDAmount", dto.getChildAmount());
        model.addAttribute("SENIORAmount", dto.getSeniorAmount());
        model.addAttribute("DISCOUNTAmount", dto.getDiscountAmount());
        model.addAttribute("totalAmount", dto.getTotalAmount());
        model.addAttribute("CHILDPrice", dto.getChildPrice());
        model.addAttribute("SENIORPrice", dto.getSeniorPrice());
        model.addAttribute("DISCOUNTPrice", dto.getDiscountPrice());
        model.addAttribute("cartId", cartId);
        return "fragments/QuickBuyTicket :: QuickBuyTicketFragment";
    }


    @PostMapping("/cart/inc")
    public String incrementTicket(@RequestParam TicketType type, @RequestParam String cartId, Model model) {
        logger.debug("Increment ticket of type: {}", type);
        TicketOrder order = orderService.getOrder(cartId);
        TicketUpdateDto dto = quickTicketService.incrementTicket(type, order);
        orderService.saveOrderToRedis(cartId, order);
        model.addAllAttributes(dto.toModelAttributes());
        model.addAttribute("cartId", cartId);
        return dto.getFragmentName();
    }

    @PostMapping("/cart/dec")
    public String decrementTicket(@RequestParam TicketType type, @RequestParam String cartId, Model model) {
        logger.debug("Decrementing ticket of type: {}", type);
        TicketOrder order = orderService.getOrder(cartId);
        TicketUpdateDto dto = quickTicketService.decrementTicket(type, order);
        orderService.saveOrderToRedis(cartId, order);
        model.addAllAttributes(dto.toModelAttributes());
        model.addAttribute("cartId", cartId);
        return dto.getFragmentName();
    }

    @PostMapping("/proceedToUserData")
    public String proceedToUserData(@RequestParam String cartId,
                                    Model model) {
        TicketOrder order = orderService.getOrder(cartId);
        BasketUpdateDto dto = quickTicketService.getBasket(order);
        model.addAttribute("cartId", cartId);
        model.addAttribute("userDto", new UserDto());
        model.addAllAttributes(dto.toModelAttributes());
        model.addAttribute("ticketList", dto.getTicketList());
        model.addAttribute("order", order);
        order.setTotalAmount((int) dto.getTotalAmount());
        logger.debug("Modal data  prepared: List<QuickTicket> = {}", dto.getTicketList());
        return "fragments/proceedToUserData :: proceedToUserDataFragment";
    }

    @PostMapping("/purchase")
    public String processOrder(@Valid @ModelAttribute("userDto") UserDto userDto,
                               BindingResult bindingResult,
                               Model model,
                               @RequestParam String cartId) {
        if (userDto.getBuyerName().isEmpty() || userDto.getEmail().isEmpty() || userDto.getPhone().isEmpty()) {
            model.addAttribute("error", "Пожалуйста уточните введенные данные.");
            return "fragments/proceedToUserData :: proceedToUserDataFragment";
        }
        if (bindingResult.hasErrors()) {
            StringBuilder error = new StringBuilder();
            error.append("Пожалуйста уточните введенные данные: ");
            for (FieldError fieldError : bindingResult.getFieldErrors()) {
                error.append(fieldError.getDefaultMessage()).append(" ");
            }
            logger.warn("Validation error: " + error);

            model.addAttribute("error", error.toString());
            model.addAttribute("userDto", userDto);
            return "fragments/proceedToUserData :: proceedToUserDataFragment";
        }

        logger.debug("User data: {}", userDto);

        TicketOrder order = orderService.getOrder(cartId);
        order.setBuyerName(userDto.getBuyerName());
        order.setPhone(userDto.getPhone());
        order.setEmail(userDto.getEmail());
        model.addAttribute("cartId", cartId);
        orderService.saveOrderToDB(order);
        boolean paymentSuccess = orderService.simulatePayment(order);
        if (paymentSuccess) {
            orderService.changeStatusToPaid(order);
            emailService.sendConfirmationEmail(userDto.getEmail());
            model.addAttribute("confirmation", "Заказ был успешно оформлен! Подтверждение отправлено на ваш e-mail!");
            logger.info("Order was successfully processed: {}", order);

        } else {
            model.addAttribute("confirmation", "Произошла ошибка при оплате. Попробуйте снова.");
            logger.error("Error during payment processing: {}", order);
        }
        return "fragments/confirmation :: confirmationFragment";
    }


    @PostMapping("/basket")
    public String getBasket(Model model, @RequestParam String cartId) {
        TicketOrder order = orderService.getOrder(cartId);
        BasketUpdateDto dto = quickTicketService.getBasket(order);
        model.addAttribute("cartId", cartId);
        model.addAllAttributes(dto.toModelAttributes());
        model.addAttribute("ticketList", dto.getTicketList());
        model.addAttribute("order", order);
        logger.debug("Basket data prepared: List<QuickTicket> = {}", dto.getTicketList());
        return dto.getFragmentName();
    }

    @GetMapping("/closeModal")
    public ResponseEntity<Void> clearSession(HttpSession httpSession) {
        logger.debug("Clearing session order");
        httpSession.removeAttribute("order");
        return ResponseEntity.noContent().build();
    }


}
