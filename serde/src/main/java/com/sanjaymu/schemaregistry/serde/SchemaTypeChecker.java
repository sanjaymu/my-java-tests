package com.sanjaymu.schemaregistry.serde;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Checks that a Java type can faithfully hold data described by a JSON schema, using the same Jackson
 * {@link ObjectMapper} that deserializes the payloads (so {@code @JsonProperty} names, records and
 * naming strategies are honoured).
 * <p>
 * <b>Errors</b> (data would be lost or mapping would fail):
 * <ul>
 *   <li>a <em>required</em> schema property has no Java property;</li>
 *   <li>incompatible types, e.g. {@code number} → {@code int}, {@code string} → {@code long},
 *       {@code array} → {@code String};</li>
 *   <li>the schema allows {@code null} but the Java type is primitive;</li>
 *   <li>schema enum values that the Java enum does not define;</li>
 *   <li>a Java property the schema forbids ({@code additionalProperties: false});</li>
 *   <li>a property {@link JavaContract} requires (primitive, {@code @NotNull}, …) that the schema does not define —
 *       every message would be rejected.</li>
 * </ul>
 * <b>Warnings</b> (typical during schema evolution): optional schema properties the class lacks
 * (ignored on read), optional Java properties the schema does not define (always {@code null}), properties
 * required in Java but optional in the schema (messages without them are rejected), and constructs that are not checked
 * ({@code oneOf}/{@code anyOf}/{@code allOf}, external {@code $ref}).
 */
public final class SchemaTypeChecker {

    private final ObjectMapper mapper;

    public SchemaTypeChecker(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Checks {@code type} against a schema given as a JSON string. */
    public TypeCheckReport check(String schema, Class<?> type) {
        try {
            return check(mapper.readTree(schema), type);
        } catch (JsonProcessingException e) {
            throw new SerdeException("Schema is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    public TypeCheckReport check(JsonNode schema, Class<?> type) {
        Run run = new Run(schema);
        run.check("$", schema, mapper.constructType(type));
        return new TypeCheckReport(run.errors, run.warnings);
    }

    private final class Run {
        final JsonNode root;
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Set<String> visited = new HashSet<>();

        Run(JsonNode root) {
            this.root = root;
        }

        void check(String path, JsonNode schemaIn, JavaType jt) {
            JsonNode schema = deref(path, schemaIn);
            if (schema == null || schema.isBoolean() || isAnyJavaType(jt)) {
                return; // unresolvable (already warned), "true"/"false" schemas, or Object/JsonNode targets
            }
            if (!visited.add(jt.toCanonical() + "@" + System.identityHashCode(schema))) {
                return; // recursive structure already checked
            }
            if (!schema.has("type") && (schema.has("oneOf") || schema.has("anyOf") || schema.has("allOf"))) {
                JsonNode single = nullableSingleBranch(schema);
                if (single == null) {
                    warnings.add(path + ": oneOf/anyOf/allOf is not checked against " + name(jt));
                    return;
                }
                // {"anyOf":[{"type":"null"}, X]} is how generators express "nullable X".
                if (jt.isPrimitive()) {
                    errors.add(path + ": schema allows null but Java type is primitive " + name(jt)
                            + " (use the wrapper type)");
                }
                visited.remove(jt.toCanonical() + "@" + System.identityHashCode(schema));
                check(path, single, jt);
                return;
            }
            Set<String> types = typesOf(schema);
            if (types.isEmpty()) {
                return; // no constraint on type
            }
            boolean nullable = types.remove("null");
            if (nullable && jt.isPrimitive()) {
                errors.add(path + ": schema allows null but Java type is primitive " + name(jt)
                        + " (use the wrapper type)");
            }
            if (types.isEmpty()) {
                return;
            }
            if (types.equals(Set.of("integer", "number"))) {
                types = Set.of("number");
            }
            if (types.size() > 1) {
                errors.add(path + ": schema allows multiple types " + types + " but Java type is " + name(jt)
                        + " (use Object or JsonNode)");
                return;
            }
            String t = types.iterator().next();
            Class<?> raw = jt.getRawClass();
            switch (t) {
                case "string" -> checkString(path, schema, jt);
                case "integer" -> {
                    if (!isIntegral(raw) && !isDecimal(raw)) {
                        mismatch(path, t, jt);
                    }
                }
                case "number" -> {
                    if (isIntegral(raw)) {
                        errors.add(path + ": schema type 'number' allows decimals but Java type " + name(jt)
                                + " is integral (use double or BigDecimal)");
                    } else if (!isDecimal(raw)) {
                        mismatch(path, t, jt);
                    }
                }
                case "boolean" -> {
                    if (raw != boolean.class && raw != Boolean.class) {
                        mismatch(path, t, jt);
                    }
                }
                case "array" -> {
                    if (jt.isArrayType() || jt.isCollectionLikeType()) {
                        JsonNode items = schema.get("items");
                        if (items != null && (items.isObject() || items.isBoolean())) {
                            check(path + "[]", items, jt.getContentType());
                        }
                    } else {
                        mismatch(path, t, jt);
                    }
                }
                case "object" -> checkObject(path, schema, jt);
                default -> warnings.add(path + ": unknown schema type '" + t + "' not checked");
            }
        }

        void checkString(String path, JsonNode schema, JavaType jt) {
            Class<?> raw = jt.getRawClass();
            if (raw.isEnum()) {
                JsonNode values = schema.get("enum");
                if (values == null) {
                    warnings.add(path + ": schema allows any string but Java type is enum " + name(jt)
                            + "; unknown values will fail to deserialize");
                    return;
                }
                List<String> unknown = new ArrayList<>();
                for (JsonNode v : values) {
                    if (v.isNull()) {
                        continue;
                    }
                    try {
                        mapper.treeToValue(v, raw);
                    } catch (JsonProcessingException | IllegalArgumentException e) {
                        unknown.add(v.asText());
                    }
                }
                if (!unknown.isEmpty()) {
                    errors.add(path + ": schema enum values " + unknown + " are not defined in Java enum " + name(jt));
                }
                return;
            }
            boolean ok = CharSequence.class.isAssignableFrom(raw) || raw == char.class || raw == Character.class
                    || raw == UUID.class || raw == URI.class || raw == URL.class || raw == byte[].class
                    || Date.class.isAssignableFrom(raw) || raw.getName().startsWith("java.time.");
            if (!ok) {
                mismatch(path, "string", jt);
            }
        }

        void checkObject(String path, JsonNode schema, JavaType jt) {
            if (jt.isMapLikeType()) {
                JsonNode additional = schema.get("additionalProperties");
                if (additional != null && additional.isObject()) {
                    check(path + "{}", additional, jt.getContentType());
                }
                return;
            }
            Class<?> raw = jt.getRawClass();
            if (jt.isPrimitive() || jt.isArrayType() || jt.isCollectionLikeType() || jt.isEnumType()
                    || raw.getName().startsWith("java.")) {
                mismatch(path, "object", jt);
                return;
            }

            Map<String, BeanPropertyDefinition> javaProps = new LinkedHashMap<>();
            for (BeanPropertyDefinition p : mapper.getDeserializationConfig().introspect(jt).findProperties()) {
                if (p.couldDeserialize()) {
                    javaProps.put(p.getName(), p);
                }
            }
            Set<String> required = new HashSet<>();
            schema.path("required").forEach(r -> required.add(r.asText()));
            JsonNode props = schema.path("properties");

            Iterator<Map.Entry<String, JsonNode>> it = props.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String name = e.getKey();
                String childPath = path + "." + name;
                BeanPropertyDefinition jp = javaProps.get(name);
                if (jp == null) {
                    if (required.contains(name)) {
                        errors.add(childPath + ": required by the schema but " + name(jt)
                                + " has no such property; its value would be lost");
                    } else {
                        warnings.add(childPath + ": optional schema property not in " + name(jt)
                                + "; it will be ignored when deserializing");
                    }
                    continue;
                }
                JavaType propType = jp.getPrimaryType();
                if (JavaContract.isRequired(jp) && !required.contains(name)) {
                    warnings.add(childPath + ": required in " + name(jt) + " but optional in the schema; "
                            + "messages without it will be rejected");
                }
                check(childPath, e.getValue(), propType);
            }
            for (String javaName : javaProps.keySet()) {
                if (props.has(javaName)) {
                    continue;
                }
                JsonNode additional = schema.get("additionalProperties");
                if (JavaContract.isRequired(javaProps.get(javaName))) {
                    errors.add(path + "." + javaName + ": required in " + name(jt)
                            + " but not defined in the schema; every message would be rejected");
                } else if (additional != null && additional.isBoolean() && !additional.asBoolean()) {
                    errors.add(path + "." + javaName + ": Java property of " + name(jt)
                            + " is not allowed by the schema (additionalProperties: false)");
                } else {
                    warnings.add(path + "." + javaName + ": Java property of " + name(jt)
                            + " is not defined in the schema; it will usually be null");
                }
            }
        }

        JsonNode deref(String path, JsonNode node) {
            Set<String> seen = new HashSet<>();
            while (node != null && node.isObject() && node.has("$ref") && node.get("$ref").isTextual()) {
                String ref = node.get("$ref").asText();
                if (!ref.startsWith("#") || !seen.add(ref)) {
                    warnings.add(path + ": $ref '" + ref + "' is not checked");
                    return null;
                }
                JsonNode target = ref.length() == 1 ? root : root.at(ref.substring(1));
                if (target.isMissingNode()) {
                    warnings.add(path + ": $ref '" + ref + "' does not resolve");
                    return null;
                }
                node = target;
            }
            return node;
        }

        void mismatch(String path, String schemaType, JavaType jt) {
            errors.add(path + ": schema type '" + schemaType + "' cannot be mapped to Java type " + name(jt));
        }
    }

    /** For {@code anyOf/oneOf: [{"type":"null"}, X]} returns X; otherwise {@code null}. */
    private static JsonNode nullableSingleBranch(JsonNode schema) {
        if (schema.has("allOf")) {
            return null;
        }
        JsonNode branches = schema.has("anyOf") ? schema.get("anyOf") : schema.get("oneOf");
        if (branches == null || !branches.isArray() || branches.size() != 2) {
            return null;
        }
        JsonNode other = null;
        boolean hasNull = false;
        for (JsonNode b : branches) {
            if (b.isObject() && b.size() == 1 && "null".equals(b.path("type").asText(null))) {
                hasNull = true;
            } else {
                other = b;
            }
        }
        return hasNull ? other : null;
    }

    private static Set<String> typesOf(JsonNode schema) {
        Set<String> types = new LinkedHashSet<>();
        if (schema == null) {
            return types;
        }
        JsonNode t = schema.get("type");
        if (t != null && t.isTextual()) {
            types.add(t.asText());
        } else if (t != null && t.isArray()) {
            t.forEach(x -> types.add(x.asText()));
        } else if (schema.has("properties")) {
            types.add("object");
        } else if (schema.has("items")) {
            types.add("array");
        } else if (schema.has("enum")) {
            schema.get("enum").forEach(v -> types.add(v.isNull() ? "null" : v.isTextual() ? "string"
                    : v.isIntegralNumber() ? "integer" : v.isNumber() ? "number" : v.isBoolean() ? "boolean" : "object"));
        }
        return types;
    }

    private static boolean isAnyJavaType(JavaType jt) {
        Class<?> raw = jt.getRawClass();
        return raw == Object.class || JsonNode.class.isAssignableFrom(raw);
    }

    private static boolean isIntegral(Class<?> c) {
        return c == int.class || c == long.class || c == short.class || c == byte.class
                || c == Integer.class || c == Long.class || c == Short.class || c == Byte.class
                || c == BigInteger.class;
    }

    private static boolean isDecimal(Class<?> c) {
        return c == double.class || c == float.class || c == Double.class || c == Float.class
                || c == BigDecimal.class || c == Number.class;
    }

    private static String name(JavaType jt) {
        Class<?> raw = jt.getRawClass();
        if (raw.isArray()) {
            return raw.getComponentType().getSimpleName() + "[]";
        }
        return jt.hasGenericTypes() ? jt.toCanonical().replaceAll("[a-zA-Z0-9_]+\\.", "") : raw.getSimpleName();
    }
}
