package com.watertours.project.controller;

import com.watertours.project.enums.TicketType;
import com.watertours.project.model.dto.QuickTicketModalDto;
import com.watertours.project.model.dto.TicketUpdateDto;
import com.watertours.project.model.order.Order;
import com.watertours.project.service.EmailService;
import com.watertours.project.service.OrderService;
import com.watertours.project.service.QuickTicketService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class QuickTicketController {

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
    public String getModal(HttpSession httpSession,Model model) {
        Order order =  orderService.getOrder(httpSession);
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


        return "fragments/QuickBuyTicket :: QuickBuyTicketFragment";
    }

    @PostMapping("/getModal")
    public String postModal(Model model,HttpSession httpSession){
        return quickTicketService.postModal(model, httpSession);
    }

    @PostMapping("/cart/inc")
    public String incrementTicket(@RequestParam TicketType type, HttpSession httpSession, Model model) {
        Order order = orderService.getOrder(httpSession);
        TicketUpdateDto dto = quickTicketService.incrementTicket(type,order);
        httpSession.setAttribute("order", order);
        model.addAllAttributes(dto.toModelAttributes());
        return dto.getFragmentName();
    }

    @PostMapping("/cart/dec")
    public String decrementTicket(@RequestParam TicketType type, HttpSession httpSession, Model model) {
        System.out.println(type + " decrement");
        Order order = orderService.getOrder(httpSession);
        TicketUpdateDto dto = quickTicketService.decrementTicket(type,order);
        httpSession.setAttribute("order", order);
        model.addAllAttributes(dto.toModelAttributes());
        return dto.getFragmentName();
    }


    @PostMapping("/proceedToUserData")
    public String proceedToUserData() {
        return quickTicketService.proceedToUserData();
    }

    @PostMapping("/basket")
    public String getBasket(Model model, HttpSession httpSession){
        return quickTicketService.getBasket(model, httpSession);
    }

    @PostMapping("/purchase")
    public String processOrder(@RequestParam("buyerName") String name,
                               @RequestParam("email") String email,
                               @RequestParam(value = "phone", required = false) String phone,
                               Model model,
                               HttpSession httpSession) {
        if (name.isEmpty() || email.isEmpty() || phone.isEmpty()) {
            model.addAttribute("error", "Пожалуйста уточните введенные данные.");
            return "fragments/userData :: userDataFragment";
        }


        Order order = orderService.getOrder(httpSession);
        order.setBuyerName(name);
        order.setTelephone(phone);
        order.setEmail(email);
        httpSession.setAttribute("order", order);

        boolean paymentSuccess = orderService.simulatePayment(order);
        if (paymentSuccess) {
            emailService.sendConfirmationEmail(email);
            model.addAttribute("confirmation", "Заказ был успешно оформлен! Подтверждение отправлено на ваш e-mail!");
        } else {
            model.addAttribute("confirmation", "Произошла ошибка при оплате. Попробуйте снова.");
        }
        return "fragments/confirmation :: confirmationFragment";
    }
}
