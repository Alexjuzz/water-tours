package com.watertours.project.component;

import com.watertours.project.enums.TicketType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
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

    public int getChild() {
        return child;
    }

    public void setChild(int child) {
        this.child = child;
    }

    public int getSenior() {
        return senior;
    }

    public void setSenior(int senior) {
        this.senior = senior;
    }

    public int getDiscount() {
        return discount;
    }

    public void setDiscount(int discount) {
        this.discount = discount;
    }

}
