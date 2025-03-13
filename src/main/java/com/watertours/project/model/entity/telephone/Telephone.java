package com.watertours.project.model.entity.telephone;

import lombok.Data;

@Data
public class Telephone {
        private final String number;

    public Telephone(String number) {
        this.number = number;
    }
}
