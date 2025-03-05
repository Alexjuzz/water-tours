package com.watertours.project.model.dto;

import lombok.Data;

@Data
public class QuickTicketModalDto {
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
}
