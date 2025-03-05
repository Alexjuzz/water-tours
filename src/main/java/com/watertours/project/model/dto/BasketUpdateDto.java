package com.watertours.project.model.dto;

import com.watertours.project.model.entity.ticket.QuickTicket;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class BasketUpdateDto {

    private final String TICKET_FRAGMENT = "fragments/basket :: basketFragment";

    private long childCount;
    private long seniorCount;
    private long discountCount;
    private long childAmount;
    private long seniorAmount;
    private long discountAmount;
    private long totalAmount;
    private long childPrice;
    private long seniorPrice;
    private long discountPrice;
    private List<QuickTicket> ticketList;


    public String getFragmentName() {
        return String.format(TICKET_FRAGMENT);
    }

    public Map<String, Object> toModelAttributes() {
        return Map.of(
                "childCount", childCount,
                "seniorCount", seniorCount,
                "discountCount", discountCount,
                "childAmount", childAmount,
                "seniorAmount", seniorAmount,
                "discountAmount", discountAmount,
                "totalAmount", totalAmount,
                "childPrice", childPrice,
                "seniorPrice", seniorPrice,
                "discountPrice", discountPrice
        );
    }
}
