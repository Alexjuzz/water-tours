package com.watertours.project.model.entity.ticket;

import lombok.Data;

@Data
public class Telephone {
        private final String number;

    public Telephone(String number) {
        this.number = number;
    }
}
