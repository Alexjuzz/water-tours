package com.watertours.project.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserDto {
    @NotBlank(message = "Поле не может быть пустым")
    @Size(min = 2, max = 30, message = "Имя должно быть от 2 до 30 символов")
    private  String buyerName;
    @NotBlank(message = "Почта не может быть пустым")
    @Email(message = "Некорректный адрес электронной почты")
    private  String email;
    @NotBlank(message = "Телефон не может быть пустым")
    @Pattern(regexp = "^\\+?[1-9]\\d{1,14}$", message = "Телефон должен быть в формате +79991234567")
    private  String phone;
}
