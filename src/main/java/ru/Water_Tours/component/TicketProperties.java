package ru.Water_Tours.component;

import lombok.Data;
import org.springframework.stereotype.Component;
import ru.Water_Tours.enums.TicketType;

import java.math.BigDecimal;


@Component
@Data
        /*
        TODO        Вынести в конфиг все свойства билетов и скидки.
         */
public class TicketProperties {

    private static BigDecimal discountChild  = BigDecimal.valueOf(0.2);
    private static BigDecimal discountAdult = BigDecimal.valueOf(0.0);
    private static BigDecimal discountBenefit = BigDecimal.valueOf(0.15);

    private static BigDecimal priceChild = BigDecimal.valueOf(1000.0);
    private static BigDecimal priceAdult = BigDecimal.valueOf(1500.0);
    private static BigDecimal priceBenefit = BigDecimal.valueOf(1200.0);

    public static BigDecimal getPriceByType(TicketType type){

        return switch (type) {
            case ADULT -> priceAdult.multiply(BigDecimal.ONE.subtract(discountAdult)) ;
            case CHILD -> priceChild.multiply(BigDecimal.ONE.subtract(discountChild));
            case BENEFIT -> priceBenefit.multiply(BigDecimal.ONE.subtract(discountBenefit));
        };
    }

}
