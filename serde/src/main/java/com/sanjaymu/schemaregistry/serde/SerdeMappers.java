package com.sanjaymu.schemaregistry.serde;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Default Jackson configuration shared by the serializer and deserializer. */
public final class SerdeMappers {

    private SerdeMappers() {
    }

    /**
     * A mapper that writes {@code java.time} values as ISO-8601 strings (matching the {@code date-time}/{@code date}
     * formats produced by {@link JavaTypeSchemaGenerator}) and ignores unknown properties on read, so older models
     * can consume newer payloads. Decimals are kept exact ({@code BigDecimal}) end to end, so monetary values
     * never pass through {@code double}.
     */
    public static ObjectMapper defaultMapper() {
        return new ObjectMapper()
                .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
