package com.sanjaymu.schemaregistry.serde;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Default Jackson configuration shared by the serializer, deserializer and {@link JavaContract}. */
public final class SerdeMappers {

    private SerdeMappers() {
    }

    /**
     * A strict mapper so that Java types act as the schema:
     * <ul>
     *   <li>no scalar coercion: {@code "10"} is not an {@code int}, {@code 10} is not a {@code String},
     *       {@code 1.5} is not an {@code int}, {@code 1} is not a {@code boolean}, {@code null} is not a primitive;</li>
     *   <li>enums only accept their names (not ordinals); trailing content is rejected;</li>
     *   <li>{@code java.time} types are written as ISO-8601 strings (matching the generated {@code date-time} formats);</li>
     *   <li>decimals stay exact ({@code BigDecimal}) end to end;</li>
     *   <li>unknown properties are ignored, so older models can consume newer payloads.</li>
     * </ul>
     */
    public static ObjectMapper defaultMapper() {
        JsonMapper mapper = JsonMapper.builder()
                .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        for (LogicalType t : new LogicalType[]{LogicalType.Integer, LogicalType.Float, LogicalType.Boolean}) {
            mapper.coercionConfigFor(t).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        }
        mapper.coercionConfigFor(LogicalType.Boolean).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
        return mapper;
    }
}
