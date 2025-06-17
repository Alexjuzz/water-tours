package com.watertours.project.interfaces.email;

import com.watertours.project.model.entity.order.TicketOrder;

public interface TicketEmailService {
    boolean sendTicketsEmail(TicketOrder order) throws Exception;

    boolean resendTicketEmail(TicketOrder order) throws Exception;

    void retrySendTicketEmail(TicketOrder order) throws Exception;
}
