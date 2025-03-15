package com.watertours.project.model.dto;

import com.watertours.project.enums.TicketType;
import lombok.Data;

import java.util.Map;

@Data
public class TicketUpdateDto {
    private final String TICKET_FRAGMENT = "fragments/QuickBuyTicket :: %sTicketFragment";
    private final TicketType type;
    private final long count;
    private final int price;
    private final int ticketAmount;
    private final  int totalAmount;




    public String getFragmentName() {
        return String.format(TICKET_FRAGMENT, type);
    }
    public Map<String, Object> toModelAttributes() {
        return Map.of(
                String.format("%sCount", type.name()), count,
                String.format("%sAmount", type.name()), ticketAmount,
                String.format("%sPrice", type.name()), price,
                "totalAmount", totalAmount
        );
    }
}
