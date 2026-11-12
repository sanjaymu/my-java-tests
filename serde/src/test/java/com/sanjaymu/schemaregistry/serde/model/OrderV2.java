package com.sanjaymu.schemaregistry.serde.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** v2 adds an optional coupon. */
public record OrderV2(long id,
                      @JsonProperty(required = true) String customer,
                      BigDecimal amount,
                      Status status,
                      UUID trackingId,
                      Instant createdAt,
                      Address shippingAddress,
                      List<String> tags,
                      Map<String, Integer> quantities,
                      String coupon) {
}
