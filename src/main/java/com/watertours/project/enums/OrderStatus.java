package com.watertours.project.enums;

import lombok.Getter;

@Getter
public enum OrderStatus {
    PAID,
    DRAFT,
    EXPIRED,
    CANCELLED,
    PENDING;

    }
