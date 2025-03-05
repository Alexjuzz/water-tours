package com.watertours.project.controller;

import com.watertours.project.enums.TicketType;
import com.watertours.project.model.dto.BasketUpdateDto;
import com.watertours.project.model.dto.QuickTicketModalDto;
import com.watertours.project.model.dto.TicketUpdateDto;
import com.watertours.project.model.dto.UserDto;
import com.watertours.project.model.order.Order;
import com.watertours.project.service.EmailService;
import com.watertours.project.service.OrderService;
import com.watertours.project.service.QuickTicketService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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
    public String getModal(HttpSession httpSession, Model model) {
        Order order = orderService.getOrder(httpSession);
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


    @PostMapping("/cart/inc")
    public String incrementTicket(@RequestParam TicketType type, HttpSession httpSession, Model model) {
        Order order = orderService.getOrder(httpSession);
        TicketUpdateDto dto = quickTicketService.incrementTicket(type, order);
        httpSession.setAttribute("order", order);
        model.addAllAttributes(dto.toModelAttributes());
        return dto.getFragmentName();
    }

    @PostMapping("/cart/dec")
    public String decrementTicket(@RequestParam TicketType type, HttpSession httpSession, Model model) {
        System.out.println(type + " decrement");
        Order order = orderService.getOrder(httpSession);
        TicketUpdateDto dto = quickTicketService.decrementTicket(type, order);
        httpSession.setAttribute("order", order);
        model.addAllAttributes(dto.toModelAttributes());
        return dto.getFragmentName();
    }

    @PostMapping("/proceedToUserData")
    public String proceedToUserData(HttpSession httpSession,
                                    Model model) {
        Order order = orderService.getOrder(httpSession);
        BasketUpdateDto dto = quickTicketService.getBasket(order);
        httpSession.setAttribute("order", order);
        model.addAttribute("userDto", new UserDto());
        model.addAllAttributes(dto.toModelAttributes());
        model.addAttribute("ticketList", dto.getTicketList());
        model.addAttribute("order", order);
        System.out.println(model.getAttribute("userDto"));
        return "fragments/proceedToUserData :: proceedToUserDataFragment";
    }
    @PostMapping("/purchase")
    public String processOrder(@Valid @ModelAttribute("userDto") UserDto userDto,
                               BindingResult bindingResult,
                               Model model,
                               HttpSession httpSession) {
        System.out.println(userDto.getEmail());
        if (userDto.getBuyerName().isEmpty() || userDto.getEmail().isEmpty() || userDto.getPhone().isEmpty()) {
            model.addAttribute("error", "Пожалуйста уточните введенные данные.");
            return "fragments/userData :: userDataFragment";
        }
        if(bindingResult.hasErrors()){
            StringBuilder error = new StringBuilder();
            error.append("Пожалуйста уточните введенные данные: ");
            error.append(bindingResult.getFieldErrors().get(0).getDefaultMessage());
            System.out.println(error);
            model.addAttribute("error", error.toString());
            model.addAttribute("userDto", userDto);
            return "fragments/proceedToUserData :: proceedToUserDataFragment";
        }
        System.out.println(httpSession.getAttribute("order"));
        Order order = orderService.getOrder(httpSession);
        order.setBuyerName(userDto.getBuyerName());
        order.setTelephone(userDto.getPhone());
        order.setEmail(userDto.getEmail());
        httpSession.setAttribute("order", order);

        boolean paymentSuccess = orderService.simulatePayment(order);
        if (paymentSuccess) {
            emailService.sendConfirmationEmail(userDto.getEmail());
            model.addAttribute("confirmation", "Заказ был успешно оформлен! Подтверждение отправлено на ваш e-mail!");
        } else {
            model.addAttribute("confirmation", "Произошла ошибка при оплате. Попробуйте снова.");
        }
        return "fragments/confirmation :: confirmationFragment";
    }


    @PostMapping("/basket")
    public String getBasket(Model model, HttpSession httpSession) {
        Order order = orderService.getOrder(httpSession);
        BasketUpdateDto dto = quickTicketService.getBasket(order);
        httpSession.setAttribute("order", order);
        model.addAllAttributes(dto.toModelAttributes());
        model.addAttribute("ticketList", dto.getTicketList());
        model.addAttribute("order", order);
        return dto.getFragmentName();
    }


}
