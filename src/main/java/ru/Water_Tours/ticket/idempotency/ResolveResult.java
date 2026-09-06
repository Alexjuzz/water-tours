package ru.Water_Tours.ticket.idempotency;

import java.util.UUID;

public record ResolveResult <T>(T value,  boolean reused) {

}
