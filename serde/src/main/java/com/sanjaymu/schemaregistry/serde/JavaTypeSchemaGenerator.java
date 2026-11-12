package com.sanjaymu.schemaregistry.serde;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;

/**
 * Generates a JSON Schema (draft-07) from a Java class, so the Java model is the single source of truth
 * for the schema a producer registers.
 * <p>
 * Mapping rules:
 * <ul>
 *   <li>Property names follow Jackson ({@code @JsonProperty}, naming strategies, records, getters/fields).</li>
 *   <li>Primitive fields ({@code int}, {@code long}, {@code double}, {@code boolean}, …) are <b>required</b>
 *       and non-nullable; fields annotated {@code @JsonProperty(required = true)} are required too.</li>
 *   <li>All other fields are optional and nullable ({@code ["string","null"]}), matching how Jackson writes
 *       {@code null} values.</li>
 *   <li>Enums become string enums; collections become arrays; maps become objects with
 *       {@code additionalProperties}; {@code UUID}/{@code java.time} types get a {@code format}.</li>
 *   <li>Additional properties are allowed, so new optional fields stay forward compatible.</li>
 * </ul>
 */
public final class JavaTypeSchemaGenerator {

    private static final SchemaGenerator GENERATOR = new SchemaGenerator(config());

    private JavaTypeSchemaGenerator() {
    }

    private static com.github.victools.jsonschema.generator.SchemaGeneratorConfig config() {
        SchemaGeneratorConfigBuilder b = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON)
                .with(new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                        JacksonOption.RESPECT_JSONPROPERTY_ORDER,
                        JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE,
                        JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY))
                .with(Option.EXTRA_OPEN_API_FORMAT_VALUES)
                .with(Option.FLATTENED_ENUMS)
                .with(Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES);
        b.forFields().withRequiredCheck(JavaTypeSchemaGenerator::isPrimitive);
        b.forFields().withNullableCheck(f -> !isPrimitive(f) && !isJacksonRequired(f));
        return b.build();
    }

    private static boolean isPrimitive(FieldScope field) {
        return field.getType() != null && field.getType().getErasedType().isPrimitive();
    }

    private static boolean isJacksonRequired(FieldScope field) {
        JsonProperty p = field.getAnnotationConsideringFieldAndGetter(JsonProperty.class);
        return p != null && p.required();
    }

    /** Returns the JSON schema for {@code type} as a compact JSON string. */
    public static String generate(Class<?> type) {
        return generateNode(type).toString();
    }

    /** Returns the JSON schema for {@code type} as a Jackson tree. */
    public static ObjectNode generateNode(Class<?> type) {
        try {
            ObjectNode schema = GENERATOR.generateSchema(type);
            if (!schema.has("title")) {
                schema.put("title", type.getSimpleName());
            }
            return schema;
        } catch (RuntimeException e) {
            throw new SerdeException("Could not generate a JSON schema for " + type.getName() + ": " + e.getMessage(), e);
        }
    }
}
