package com.sanjaymu.schemaregistry.serde.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A producer model used to generate the v1 schema. */
public record OrderV1(long id,
                      @JsonProperty(required = true) String customer,
                      BigDecimal amount,
                      Status status,
                      UUID trackingId,
                      Instant createdAt,
                      Address shippingAddress,
                      List<String> tags,
                      Map<String, Integer> quantities) {
}
