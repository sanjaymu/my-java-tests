package com.example.orders;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Example producer/consumer model. The Java code is the schema:
 * primitives and @NotNull/@NotBlank fields are required; constraints are enforced on both sides
 * and copied into the registered JSON schema.
 */
public record Order(long id,
                    @NotBlank String customer,
                    @NotNull @PositiveOrZero BigDecimal amount,
                    @Size(max = 20) String coupon,
                    Instant createdAt) {
}
