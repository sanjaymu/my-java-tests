package com.sanjaymu.schemaregistry.local;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanjaymu.schemaregistry.client.model.CompatibilityLevel;
import com.sanjaymu.schemaregistry.client.model.CompatibilityResult;
import com.sanjaymu.schemaregistry.client.model.Mode;
import com.sanjaymu.schemaregistry.client.model.RegisterSchemaRequest;
import com.sanjaymu.schemaregistry.client.model.Schema;
import com.sanjaymu.schemaregistry.client.model.SchemaReference;
import com.sanjaymu.schemaregistry.client.model.SchemaType;
import com.sanjaymu.schemaregistry.client.model.SchemaVersion;
import com.sanjaymu.schemaregistry.client.model.SubjectVersion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Thread-safe, in-memory implementation of the Schema Registry data model.
 * <p>
 * Supports subjects, versions, soft/hard deletes, schema references, global and per-subject
 * compatibility levels and modes. Compatibility enforcement is implemented for Avro; JSON and
 * Protobuf schemas are syntax-checked only and treated as compatible.
 */
public final class InMemorySchemaRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record StoredSchema(int id, SchemaType type, String schema, List<SchemaReference> references) {
    }

    private static final class VersionEntry {
        final int version;
        final int id;
        boolean deleted;

        VersionEntry(int version, int id) {
            this.version = version;
            this.id = id;
        }
    }

    private final Map<Integer, StoredSchema> schemasById = new HashMap<>();
    private final Map<String, Integer> idsByFingerprint = new HashMap<>();
    private final Map<String, List<VersionEntry>> subjects = new TreeMap<>();
    private final Map<String, CompatibilityLevel> subjectCompatibility = new HashMap<>();
    private final Map<String, Mode> subjectModes = new HashMap<>();
    private CompatibilityLevel globalCompatibility = CompatibilityLevel.BACKWARD;
    private Mode globalMode = Mode.READWRITE;
    private int nextId = 1;

    // ------------------------------------------------------------------ subjects

    public synchronized List<String> subjects(String prefix, boolean includeDeleted) {
        List<String> out = new ArrayList<>();
        subjects.forEach((name, versions) -> {
            if (prefix != null && !name.startsWith(prefix)) {
                return;
            }
            if (includeDeleted || versions.stream().anyMatch(v -> !v.deleted)) {
                out.add(name);
            }
        });
        return out;
    }

    public synchronized List<Integer> versions(String subject, boolean includeDeleted) {
        List<VersionEntry> entries = subjects.get(subject);
        if (entries == null || (!includeDeleted && entries.stream().allMatch(v -> v.deleted))) {
            throw RegistryError.subjectNotFound(subject);
        }
        return entries.stream().filter(v -> includeDeleted || !v.deleted).map(v -> v.version).toList();
    }

    public synchronized List<Integer> deleteSubject(String subject, boolean permanent) {
        ensureWritable(subject);
        List<VersionEntry> entries = subjects.get(subject);
        if (entries == null) {
            throw RegistryError.subjectNotFound(subject);
        }
        if (permanent) {
            if (entries.stream().anyMatch(v -> !v.deleted)) {
                throw new RegistryError(404, 40405,
                        "Subject '" + subject + "' was not deleted first before being permanently deleted");
            }
            subjects.remove(subject);
            subjectCompatibility.remove(subject);
            subjectModes.remove(subject);
            return entries.stream().map(v -> v.version).toList();
        }
        List<Integer> deleted = entries.stream().filter(v -> !v.deleted).map(v -> v.version).toList();
        if (deleted.isEmpty()) {
            throw new RegistryError(404, 40404, "Subject '" + subject + "' was soft deleted.");
        }
        entries.forEach(v -> v.deleted = true);
        return deleted;
    }

    // ------------------------------------------------------------------ versions

    public synchronized SchemaVersion version(String subject, String version) {
        VersionEntry entry = findVersion(subject, version);
        return toSchemaVersion(subject, entry);
    }

    public synchronized int register(String subject, RegisterSchemaRequest request) {
        ensureWritable(subject);
        StoredSchema candidate = normalize(request);
        List<VersionEntry> entries = subjects.computeIfAbsent(subject, s -> new ArrayList<>());

        Integer existingId = idsByFingerprint.get(fingerprint(candidate));
        if (existingId != null) {
            for (VersionEntry v : entries) {
                if (!v.deleted && v.id == existingId) {
                    return existingId;
                }
            }
        }

        List<String> errors = checkAgainstSubject(subject, candidate, entries.stream().filter(v -> !v.deleted).toList(),
                effectiveCompatibility(subject));
        if (!errors.isEmpty()) {
            if (entries.isEmpty()) {
                subjects.remove(subject);
            }
            throw new RegistryError(409, 409, "Schema being registered is incompatible with an earlier schema for subject '"
                    + subject + "', details: " + errors);
        }

        int id = existingId != null ? existingId : storeNew(candidate);
        int nextVersion = entries.stream().mapToInt(v -> v.version).max().orElse(0) + 1;
        entries.add(new VersionEntry(nextVersion, id));
        return id;
    }

    public synchronized SchemaVersion lookup(String subject, RegisterSchemaRequest request) {
        List<VersionEntry> entries = subjects.get(subject);
        if (entries == null || entries.stream().allMatch(v -> v.deleted)) {
            throw RegistryError.subjectNotFound(subject);
        }
        StoredSchema candidate = normalize(request);
        Integer id = idsByFingerprint.get(fingerprint(candidate));
        if (id != null) {
            for (VersionEntry v : entries) {
                if (!v.deleted && v.id == id) {
                    return toSchemaVersion(subject, v);
                }
            }
        }
        throw RegistryError.schemaNotFound();
    }

    public synchronized int deleteVersion(String subject, String version, boolean permanent) {
        ensureWritable(subject);
        List<VersionEntry> entries = subjects.get(subject);
        if (entries == null) {
            throw RegistryError.subjectNotFound(subject);
        }
        VersionEntry entry = permanent ? findVersionIncludingDeleted(subject, entries, version)
                : findVersion(subject, version);
        if (permanent) {
            if (!entry.deleted) {
                throw new RegistryError(404, 40407, "Subject '" + subject + "' Version " + entry.version
                        + " was not deleted first before being permanently deleted");
            }
            entries.remove(entry);
            if (entries.isEmpty()) {
                subjects.remove(subject);
            }
        } else {
            entry.deleted = true;
        }
        return entry.version;
    }

    public synchronized List<Integer> referencedBy(String subject, String version) {
        VersionEntry entry = findVersion(subject, version);
        List<Integer> ids = new ArrayList<>();
        schemasById.values().forEach(s -> {
            for (SchemaReference r : s.references()) {
                if (subject.equals(r.subject()) && r.version() != null && r.version() == entry.version) {
                    ids.add(s.id());
                }
            }
        });
        ids.sort(Integer::compare);
        return ids;
    }

    // ------------------------------------------------------------------ schemas

    public synchronized Schema schemaById(int id) {
        StoredSchema s = schemasById.get(id);
        if (s == null) {
            throw RegistryError.schemaNotFound();
        }
        return new Schema(s.schema(), wireType(s.type()), s.references());
    }

    public synchronized List<SubjectVersion> subjectVersionsById(int id) {
        if (!schemasById.containsKey(id)) {
            throw RegistryError.schemaNotFound();
        }
        List<SubjectVersion> out = new ArrayList<>();
        subjects.forEach((name, entries) -> entries.stream()
                .filter(v -> !v.deleted && v.id == id)
                .forEach(v -> out.add(new SubjectVersion(name, v.version))));
        return out;
    }

    public List<String> schemaTypes() {
        return List.of(SchemaType.JSON.name(), SchemaType.PROTOBUF.name(), SchemaType.AVRO.name());
    }

    // ------------------------------------------------------------------ compatibility

    /** Tests against a specific version ({@code version != null}) or per the subject's level ({@code null}). */
    public synchronized CompatibilityResult testCompatibility(String subject, String version,
                                                              RegisterSchemaRequest request, boolean verbose) {
        StoredSchema candidate = normalize(request);
        List<String> errors;
        if (version != null) {
            VersionEntry entry = findVersion(subject, version);
            CompatibilityLevel level = effectiveCompatibility(subject);
            if (level.isTransitive()) {
                level = CompatibilityLevel.valueOf(level.name().replace("_TRANSITIVE", ""));
            }
            errors = checkAgainstSubject(subject, candidate, List.of(entry), level);
        } else {
            List<VersionEntry> entries = subjects.getOrDefault(subject, List.of()).stream()
                    .filter(v -> !v.deleted).toList();
            errors = checkAgainstSubject(subject, candidate, entries, effectiveCompatibility(subject));
        }
        return new CompatibilityResult(errors.isEmpty(), verbose ? errors : List.of());
    }

    // ------------------------------------------------------------------ config & mode

    public synchronized CompatibilityLevel globalCompatibility() {
        return globalCompatibility;
    }

    public synchronized void setGlobalCompatibility(CompatibilityLevel level) {
        this.globalCompatibility = level;
    }

    public synchronized CompatibilityLevel subjectCompatibility(String subject, boolean defaultToGlobal) {
        CompatibilityLevel level = subjectCompatibility.get(subject);
        if (level == null) {
            if (defaultToGlobal) {
                return globalCompatibility;
            }
            throw new RegistryError(404, 40408, "Subject '" + subject + "' does not have subject-level compatibility configured");
        }
        return level;
    }

    public synchronized void setSubjectCompatibility(String subject, CompatibilityLevel level) {
        subjectCompatibility.put(subject, level);
    }

    public synchronized CompatibilityLevel deleteSubjectCompatibility(String subject) {
        CompatibilityLevel removed = subjectCompatibility.remove(subject);
        if (removed == null) {
            throw RegistryError.subjectNotFound(subject);
        }
        return removed;
    }

    public synchronized Mode globalMode() {
        return globalMode;
    }

    public synchronized void setGlobalMode(Mode mode) {
        this.globalMode = mode;
    }

    public synchronized Mode subjectMode(String subject, boolean defaultToGlobal) {
        Mode mode = subjectModes.get(subject);
        if (mode == null) {
            if (defaultToGlobal) {
                return globalMode;
            }
            throw new RegistryError(404, 40409, "Subject '" + subject + "' does not have subject-level mode configured");
        }
        return mode;
    }

    public synchronized void setSubjectMode(String subject, Mode mode) {
        subjectModes.put(subject, mode);
    }

    public synchronized Mode deleteSubjectMode(String subject) {
        Mode removed = subjectModes.remove(subject);
        if (removed == null) {
            throw RegistryError.subjectNotFound(subject);
        }
        return removed;
    }

    // ------------------------------------------------------------------ internals

    private CompatibilityLevel effectiveCompatibility(String subject) {
        return subjectCompatibility.getOrDefault(subject, globalCompatibility);
    }

    private void ensureWritable(String subject) {
        Mode mode = subjectModes.getOrDefault(subject, globalMode);
        if (mode == Mode.READONLY) {
            throw new RegistryError(422, 42205, "Subject '" + subject + "' is in read-only mode");
        }
    }

    private VersionEntry findVersion(String subject, String version) {
        List<VersionEntry> entries = subjects.get(subject);
        if (entries == null || entries.stream().allMatch(v -> v.deleted)) {
            throw RegistryError.subjectNotFound(subject);
        }
        List<VersionEntry> live = entries.stream().filter(v -> !v.deleted).toList();
        if ("latest".equalsIgnoreCase(version) || "-1".equals(version)) {
            return live.get(live.size() - 1);
        }
        int n = parseVersion(version);
        return live.stream().filter(v -> v.version == n).findFirst()
                .orElseThrow(() -> RegistryError.versionNotFound(subject, version));
    }

    private static VersionEntry findVersionIncludingDeleted(String subject, List<VersionEntry> entries, String version) {
        if ("latest".equalsIgnoreCase(version) || "-1".equals(version)) {
            return entries.get(entries.size() - 1);
        }
        int n = parseVersion(version);
        return entries.stream().filter(v -> v.version == n).findFirst()
                .orElseThrow(() -> RegistryError.versionNotFound(subject, version));
    }

    private static int parseVersion(String version) {
        try {
            int n = Integer.parseInt(version);
            if (n < 1) {
                throw RegistryError.invalidVersion(version);
            }
            return n;
        } catch (NumberFormatException e) {
            throw RegistryError.invalidVersion(version);
        }
    }

    private SchemaVersion toSchemaVersion(String subject, VersionEntry entry) {
        StoredSchema s = schemasById.get(entry.id);
        return new SchemaVersion(subject, s.id(), entry.version, wireType(s.type()), s.schema(), s.references());
    }

    private static String wireType(SchemaType type) {
        return type == SchemaType.AVRO ? null : type.name();
    }

    private StoredSchema normalize(RegisterSchemaRequest request) {
        SchemaType type;
        try {
            type = request.type();
        } catch (IllegalArgumentException e) {
            throw RegistryError.invalidSchema("unsupported schema type '" + request.schemaType() + "'");
        }
        for (SchemaReference ref : request.references()) {
            if (ref.name() == null || ref.subject() == null || ref.version() == null) {
                throw RegistryError.invalidSchema("references require name, subject and version");
            }
            List<VersionEntry> entries = subjects.get(ref.subject());
            if (entries == null || entries.stream().noneMatch(v -> !v.deleted && v.version == ref.version())) {
                throw RegistryError.invalidSchema("reference '" + ref.name() + "' points to unknown subject/version "
                        + ref.subject() + "/" + ref.version());
            }
        }
        String schema = request.schema();
        if (schema.isBlank()) {
            throw RegistryError.invalidSchema("schema must not be empty");
        }
        String canonical = switch (type) {
            case AVRO -> {
                JsonNode node = parseJson(schema);
                if (request.references().isEmpty()) {
                    AvroCompatibilityChecker.validate(node);
                }
                yield node.toString();
            }
            case JSON -> {
                JsonNode node = parseJson(schema);
                if (!node.isObject() && !node.isBoolean()) {
                    throw RegistryError.invalidSchema("JSON schema must be an object or boolean");
                }
                yield node.toString();
            }
            case PROTOBUF -> schema.strip();
        };
        return new StoredSchema(0, type, canonical, request.references());
    }

    private static JsonNode parseJson(String schema) {
        try {
            return MAPPER.readTree(schema);
        } catch (JsonProcessingException e) {
            throw RegistryError.invalidSchema(e.getOriginalMessage());
        }
    }

    private static String fingerprint(StoredSchema s) {
        return s.type() + "|" + s.references() + "|" + s.schema();
    }

    private int storeNew(StoredSchema candidate) {
        int id = nextId++;
        StoredSchema stored = new StoredSchema(id, candidate.type(), candidate.schema(), candidate.references());
        schemasById.put(id, stored);
        idsByFingerprint.put(fingerprint(stored), id);
        return id;
    }

    private List<String> checkAgainstSubject(String subject, StoredSchema candidate, List<VersionEntry> previous,
                                             CompatibilityLevel level) {
        if (level == CompatibilityLevel.NONE || previous.isEmpty()) {
            return List.of();
        }
        List<VersionEntry> targets = level.isTransitive() ? previous : List.of(previous.get(previous.size() - 1));
        List<String> errors = new ArrayList<>();
        for (VersionEntry target : targets) {
            StoredSchema existing = schemasById.get(target.id);
            if (existing.type() != candidate.type()) {
                errors.add("Incompatible schema type: version " + target.version + " is " + existing.type()
                        + " but the new schema is " + candidate.type());
                continue;
            }
            if (candidate.type() != SchemaType.AVRO) {
                continue; // only Avro compatibility is enforced locally
            }
            JsonNode newSchema = parseJson(candidate.schema());
            JsonNode oldSchema = parseJson(existing.schema());
            if (level.isBackward()) {
                AvroCompatibilityChecker.canRead(newSchema, oldSchema).forEach(m ->
                        errors.add("BACKWARD (version " + target.version + "): " + m));
            }
            if (level.isForward()) {
                AvroCompatibilityChecker.canRead(oldSchema, newSchema).forEach(m ->
                        errors.add("FORWARD (version " + target.version + "): " + m));
            }
        }
        return errors;
    }
}
