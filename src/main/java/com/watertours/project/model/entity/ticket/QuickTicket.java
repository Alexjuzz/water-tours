package com.watertours.project.model.entity.ticket;

import com.watertours.project.enums.TicketType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

@Data
public class QuickTicket  implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private  final  UUID uuid = UUID.randomUUID();
    private final TicketType type;
    private int Price;


    public QuickTicket(TicketType type) {
        this.type = type;
    }

}
