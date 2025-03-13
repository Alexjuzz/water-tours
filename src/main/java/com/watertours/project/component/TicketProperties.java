package com.watertours.project.component;

import com.watertours.project.enums.TicketType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Data
@ConfigurationProperties(prefix = "values.ticket.price")
public class TicketProperties {
    private int child;
    private int senior;
    private int discount;


    public int getPriceByType(TicketType type) {
        return switch (type) {
            case CHILD -> getChild();
            case SENIOR -> getSenior();
            case DISCOUNT -> getDiscount();
            default -> throw new IllegalArgumentException("Неизвестнная цена");
        };
    }
}
