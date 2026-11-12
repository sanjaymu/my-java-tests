package com.example.orders;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/** Example producer model. Primitives and @JsonProperty(required = true) fields become required in the schema. */
public record Order(long id,
                    @JsonProperty(required = true) String customer,
                    BigDecimal amount,
                    Instant createdAt) {
}
