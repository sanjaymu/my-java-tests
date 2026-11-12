package com.sanjaymu.schemaregistry.local;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A pragmatic implementation of Avro schema-resolution rules used to decide whether a
 * <em>reader</em> schema can decode data written with a <em>writer</em> schema.
 * <p>
 * It covers primitives (with numeric/string promotions), records (field defaults and aliases),
 * enums, arrays, maps, fixed, unions and named-type references. It is intended for local
 * development and testing, not as a byte-for-byte replica of the Avro library.
 */
final class AvroCompatibilityChecker {

    private static final Set<String> PRIMITIVES =
            Set.of("null", "boolean", "int", "long", "float", "double", "bytes", "string");

    private AvroCompatibilityChecker() {
    }

    /** Validates that the JSON is a structurally plausible Avro schema. */
    static void validate(JsonNode schema) {
        validate(schema, new HashSet<>(), null);
    }

    private static void validate(JsonNode node, Set<String> names, String namespace) {
        if (node.isTextual()) {
            String t = node.asText();
            if (!PRIMITIVES.contains(t) && !names.contains(t) && !names.contains(qualify(t, namespace))) {
                throw RegistryError.invalidSchema("unknown type '" + t + "'");
            }
            return;
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                throw RegistryError.invalidSchema("union must not be empty");
            }
            for (JsonNode branch : node) {
                validate(branch, names, namespace);
            }
            return;
        }
        if (!node.isObject() || !node.has("type")) {
            throw RegistryError.invalidSchema("schema must be a type name, union or object with a 'type'");
        }
        JsonNode typeNode = node.get("type");
        if (!typeNode.isTextual()) {
            validate(typeNode, names, namespace);
            return;
        }
        String type = typeNode.asText();
        switch (type) {
            case "record", "error" -> {
                String ns = node.hasNonNull("namespace") ? node.get("namespace").asText() : namespace;
                names.add(qualify(requireName(node), ns));
                names.add(requireName(node));
                JsonNode fields = node.get("fields");
                if (fields == null || !fields.isArray()) {
                    throw RegistryError.invalidSchema("record '" + requireName(node) + "' must have a 'fields' array");
                }
                Set<String> seen = new HashSet<>();
                for (JsonNode f : fields) {
                    if (!f.hasNonNull("name") || !f.has("type")) {
                        throw RegistryError.invalidSchema("record fields require 'name' and 'type'");
                    }
                    if (!seen.add(f.get("name").asText())) {
                        throw RegistryError.invalidSchema("duplicate field '" + f.get("name").asText() + "'");
                    }
                    validate(f.get("type"), names, ns);
                }
            }
            case "enum" -> {
                String ns = node.hasNonNull("namespace") ? node.get("namespace").asText() : namespace;
                names.add(qualify(requireName(node), ns));
                names.add(requireName(node));
                if (!node.has("symbols") || !node.get("symbols").isArray()) {
                    throw RegistryError.invalidSchema("enum must have a 'symbols' array");
                }
            }
            case "fixed" -> {
                String ns = node.hasNonNull("namespace") ? node.get("namespace").asText() : namespace;
                names.add(qualify(requireName(node), ns));
                names.add(requireName(node));
                if (!node.has("size") || !node.get("size").canConvertToInt()) {
                    throw RegistryError.invalidSchema("fixed must have an integer 'size'");
                }
            }
            case "array" -> {
                if (!node.has("items")) {
                    throw RegistryError.invalidSchema("array must have 'items'");
                }
                validate(node.get("items"), names, namespace);
            }
            case "map" -> {
                if (!node.has("values")) {
                    throw RegistryError.invalidSchema("map must have 'values'");
                }
                validate(node.get("values"), names, namespace);
            }
            default -> validate(typeNode, names, namespace);
        }
    }

    private static String requireName(JsonNode node) {
        if (!node.hasNonNull("name") || node.get("name").asText().isBlank()) {
            throw RegistryError.invalidSchema("named types require a 'name'");
        }
        return node.get("name").asText();
    }

    private static String qualify(String name, String namespace) {
        if (name.contains(".") || namespace == null || namespace.isEmpty()) {
            return name;
        }
        return namespace + "." + name;
    }

    /**
     * Returns a list of incompatibility messages; empty when {@code reader} can read data written by {@code writer}.
     */
    static List<String> canRead(JsonNode reader, JsonNode writer) {
        Ctx ctx = new Ctx();
        ctx.collectNames(reader, ctx.readerNames, null);
        ctx.collectNames(writer, ctx.writerNames, null);
        List<String> errors = new ArrayList<>();
        check(ctx, reader, writer, "/", errors);
        return errors;
    }

    private static final class Ctx {
        final Map<String, JsonNode> readerNames = new HashMap<>();
        final Map<String, JsonNode> writerNames = new HashMap<>();
        final Set<String> inProgress = new HashSet<>();

        void collectNames(JsonNode node, Map<String, JsonNode> into, String namespace) {
            if (node.isArray()) {
                node.forEach(n -> collectNames(n, into, namespace));
                return;
            }
            if (!node.isObject()) {
                return;
            }
            JsonNode t = node.get("type");
            if (t != null && !t.isTextual()) {
                collectNames(t, into, namespace);
                return;
            }
            String type = t == null ? "" : t.asText();
            String ns = node.hasNonNull("namespace") ? node.get("namespace").asText() : namespace;
            if (Set.of("record", "error", "enum", "fixed").contains(type) && node.hasNonNull("name")) {
                String name = node.get("name").asText();
                into.put(name, node);
                into.put(qualify(name, ns), node);
                int dot = name.lastIndexOf('.');
                if (dot >= 0) {
                    into.put(name.substring(dot + 1), node);
                }
            }
            if (node.has("fields")) {
                node.get("fields").forEach(f -> collectNames(f.get("type"), into, ns));
            }
            if (node.has("items")) {
                collectNames(node.get("items"), into, ns);
            }
            if (node.has("values")) {
                collectNames(node.get("values"), into, ns);
            }
        }
    }

    private static JsonNode resolve(JsonNode node, Map<String, JsonNode> names) {
        if (node.isTextual() && !PRIMITIVES.contains(node.asText())) {
            JsonNode named = names.get(node.asText());
            return named != null ? named : node;
        }
        if (node.isObject() && node.has("type") && !node.get("type").isTextual()) {
            return resolve(node.get("type"), names);
        }
        if (node.isObject() && node.has("type") && node.get("type").isTextual()) {
            String t = node.get("type").asText();
            if (!Set.of("record", "error", "enum", "fixed", "array", "map").contains(t)) {
                // e.g. {"type":"string","logicalType":"uuid"} or a reference written as an object
                return resolve(node.get("type"), names);
            }
        }
        return node;
    }

    private static String typeOf(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            return "union";
        }
        String t = node.get("type").asText();
        return "error".equals(t) ? "record" : t;
    }

    private static String fullName(JsonNode named) {
        String name = named.get("name").asText();
        if (name.contains(".")) {
            return name;
        }
        return named.hasNonNull("namespace") ? named.get("namespace").asText() + "." + name : name;
    }

    private static boolean namesMatch(JsonNode reader, JsonNode writer) {
        String rn = reader.get("name").asText();
        String wn = writer.get("name").asText();
        if (simple(rn).equals(simple(wn))) {
            return true;
        }
        if (reader.has("aliases")) {
            for (JsonNode a : reader.get("aliases")) {
                if (simple(a.asText()).equals(simple(wn))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String simple(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private static void check(Ctx ctx, JsonNode readerRaw, JsonNode writerRaw, String path, List<String> errors) {
        JsonNode reader = resolve(readerRaw, ctx.readerNames);
        JsonNode writer = resolve(writerRaw, ctx.writerNames);
        String rt = typeOf(reader);
        String wt = typeOf(writer);

        if ("union".equals(wt)) {
            // Every writer branch must be readable by the reader.
            for (JsonNode branch : writer) {
                List<String> branchErrors = new ArrayList<>();
                check(ctx, reader, branch, path, branchErrors);
                if (!branchErrors.isEmpty()) {
                    errors.add("Reader at '" + path + "' cannot read writer union branch '" + describe(branch) + "'");
                }
            }
            return;
        }
        if ("union".equals(rt)) {
            for (JsonNode branch : reader) {
                List<String> branchErrors = new ArrayList<>();
                check(ctx, branch, writer, path, branchErrors);
                if (branchErrors.isEmpty()) {
                    return;
                }
            }
            errors.add("Reader union at '" + path + "' has no branch matching writer type '" + describe(writer) + "'");
            return;
        }

        if (PRIMITIVES.contains(wt) || PRIMITIVES.contains(rt)) {
            if (!primitivePromotable(wt, rt)) {
                errors.add("Type mismatch at '" + path + "': reader type '" + rt + "' cannot read writer type '" + wt + "'");
            }
            return;
        }
        if (!rt.equals(wt)) {
            errors.add("Type mismatch at '" + path + "': reader type '" + rt + "' cannot read writer type '" + wt + "'");
            return;
        }
        switch (rt) {
            case "record" -> checkRecord(ctx, reader, writer, path, errors);
            case "enum" -> checkEnum(reader, writer, path, errors);
            case "fixed" -> {
                if (!namesMatch(reader, writer)) {
                    errors.add("Name mismatch at '" + path + "': expected '" + fullName(writer) + "'");
                } else if (reader.get("size").asInt() != writer.get("size").asInt()) {
                    errors.add("Fixed size mismatch at '" + path + "'");
                }
            }
            case "array" -> check(ctx, reader.get("items"), writer.get("items"), path + "items/", errors);
            case "map" -> check(ctx, reader.get("values"), writer.get("values"), path + "values/", errors);
            default -> {
                // Unresolved named reference: compare by name.
                if (!reader.equals(writer)) {
                    errors.add("Unresolved type at '" + path + "': '" + rt + "'");
                }
            }
        }
    }

    private static void checkRecord(Ctx ctx, JsonNode reader, JsonNode writer, String path, List<String> errors) {
        if (!namesMatch(reader, writer)) {
            errors.add("Name mismatch at '" + path + "': reader record '" + fullName(reader)
                    + "' does not match writer record '" + fullName(writer) + "'");
            return;
        }
        String key = fullName(reader) + "<-" + fullName(writer);
        if (!ctx.inProgress.add(key)) {
            return; // recursive type already being checked
        }
        try {
            Map<String, JsonNode> writerFields = new HashMap<>();
            for (JsonNode f : writer.get("fields")) {
                writerFields.put(f.get("name").asText(), f);
            }
            for (JsonNode rf : reader.get("fields")) {
                String name = rf.get("name").asText();
                JsonNode wf = writerFields.get(name);
                if (wf == null && rf.has("aliases")) {
                    Iterator<JsonNode> it = rf.get("aliases").elements();
                    while (wf == null && it.hasNext()) {
                        wf = writerFields.get(it.next().asText());
                    }
                }
                String fieldPath = path + "fields/" + name + "/";
                if (wf == null) {
                    if (!rf.has("default")) {
                        errors.add("Reader field '" + name + "' at '" + path
                                + "' is missing from the writer schema and has no default value");
                    }
                } else {
                    check(ctx, rf.get("type"), wf.get("type"), fieldPath, errors);
                }
            }
        } finally {
            ctx.inProgress.remove(key);
        }
    }

    private static void checkEnum(JsonNode reader, JsonNode writer, String path, List<String> errors) {
        if (!namesMatch(reader, writer)) {
            errors.add("Name mismatch at '" + path + "': expected enum '" + fullName(writer) + "'");
            return;
        }
        if (reader.has("default")) {
            return; // unknown symbols resolve to the default
        }
        Set<String> readerSymbols = new HashSet<>();
        reader.get("symbols").forEach(s -> readerSymbols.add(s.asText()));
        List<String> missing = new ArrayList<>();
        writer.get("symbols").forEach(s -> {
            if (!readerSymbols.contains(s.asText())) {
                missing.add(s.asText());
            }
        });
        if (!missing.isEmpty()) {
            errors.add("Reader enum at '" + path + "' is missing writer symbols " + missing);
        }
    }

    private static boolean primitivePromotable(String writer, String reader) {
        if (writer.equals(reader)) {
            return true;
        }
        return switch (writer) {
            case "int" -> Set.of("long", "float", "double").contains(reader);
            case "long" -> Set.of("float", "double").contains(reader);
            case "float" -> "double".equals(reader);
            case "string" -> "bytes".equals(reader);
            case "bytes" -> "string".equals(reader);
            default -> false;
        };
    }

    private static String describe(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isObject() && node.hasNonNull("name")) {
            return node.get("name").asText();
        }
        return node.isObject() && node.has("type") ? node.get("type").toString() : node.toString();
    }
}
