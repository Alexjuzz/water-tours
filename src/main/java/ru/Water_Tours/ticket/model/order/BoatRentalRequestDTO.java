package ru.Water_Tours.ticket.model.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.Water_Tours.enums.BoatRouteType;

public record BoatRentalRequestDTO(
        @NotNull Integer durationMinutes,
        @NotNull @Min(1) @Max(6) Integer guestCount,
        @NotNull BoatRouteType routeType,
        @Size(max = 300) String routeNote
) {
}
