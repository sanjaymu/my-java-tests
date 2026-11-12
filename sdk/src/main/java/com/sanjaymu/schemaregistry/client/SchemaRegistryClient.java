package com.sanjaymu.schemaregistry.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanjaymu.schemaregistry.client.model.CompatibilityLevel;
import com.sanjaymu.schemaregistry.client.model.CompatibilityResult;
import com.sanjaymu.schemaregistry.client.model.ErrorMessage;
import com.sanjaymu.schemaregistry.client.model.Mode;
import com.sanjaymu.schemaregistry.client.model.RegisterSchemaRequest;
import com.sanjaymu.schemaregistry.client.model.Schema;
import com.sanjaymu.schemaregistry.client.model.SchemaVersion;
import com.sanjaymu.schemaregistry.client.model.SubjectVersion;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe client for Confluent-compatible Schema Registry REST APIs.
 *
 * <pre>{@code
 * SchemaRegistryClient client = SchemaRegistryClient.builder()
 *         .baseUrl("http://localhost:8081")
 *         .basicAuth("user", "secret")
 *         .build();
 *
 * int id = client.register("orders-value", RegisterSchemaRequest.avro(avroSchemaJson));
 * SchemaVersion latest = client.getLatestSchema("orders-value");
 * }</pre>
 *
 * Schemas fetched by id are immutable in the registry and are cached in memory.
 */
public final class SchemaRegistryClient {

    /** Version alias accepted wherever a version is expected. */
    public static final String LATEST = "latest";

    static final String CONTENT_TYPE = "application/vnd.schemaregistry.v1+json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final URI baseUri;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final Map<String, String> headers;
    private final int maxRetries;
    private final Duration retryBackoff;
    private final Map<Integer, Schema> schemaByIdCache = new ConcurrentHashMap<>();

    private SchemaRegistryClient(Builder b) {
        String url = Objects.requireNonNull(b.baseUrl, "baseUrl is required");
        this.baseUri = URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
        this.http = b.httpClient != null ? b.httpClient : HttpClient.newBuilder()
                .connectTimeout(b.connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.requestTimeout = b.requestTimeout;
        this.headers = Map.copyOf(b.headers);
        this.maxRetries = b.maxRetries;
        this.retryBackoff = b.retryBackoff;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Convenience factory for an unauthenticated client. */
    public static SchemaRegistryClient create(String baseUrl) {
        return builder().baseUrl(baseUrl).build();
    }

    public URI baseUri() {
        return baseUri;
    }

    // ------------------------------------------------------------------ subjects

    /** Lists all subjects. */
    public List<String> getSubjects() {
        return getSubjects(null, false);
    }

    /**
     * Lists subjects.
     *
     * @param prefix         only return subjects starting with this prefix ({@code null} for all)
     * @param includeDeleted include soft-deleted subjects
     */
    public List<String> getSubjects(String prefix, boolean includeDeleted) {
        Query q = new Query();
        if (prefix != null && !prefix.isEmpty()) {
            q.add("subjectPrefix", prefix);
        }
        if (includeDeleted) {
            q.add("deleted", "true");
        }
        return send("GET", "/subjects" + q, null, new TypeReference<>() { });
    }

    /** Lists the versions registered under a subject. */
    public List<Integer> getVersions(String subject) {
        return getVersions(subject, false);
    }

    public List<Integer> getVersions(String subject, boolean includeDeleted) {
        Query q = new Query();
        if (includeDeleted) {
            q.add("deleted", "true");
        }
        return send("GET", "/subjects/" + enc(subject) + "/versions" + q, null, new TypeReference<>() { });
    }

    /**
     * Deletes a subject.
     *
     * @param permanent {@code false} for a soft delete; {@code true} for a hard delete
     *                  (the subject must already be soft-deleted)
     * @return the versions that were deleted
     */
    public List<Integer> deleteSubject(String subject, boolean permanent) {
        return send("DELETE", "/subjects/" + enc(subject) + new Query().addIf(permanent, "permanent", "true"),
                null, new TypeReference<>() { });
    }

    // ------------------------------------------------------------------ versions

    /** Fetches a schema by subject and version number or {@code "latest"}. */
    public SchemaVersion getSchemaVersion(String subject, String version) {
        return send("GET", "/subjects/" + enc(subject) + "/versions/" + enc(version), null, new TypeReference<>() { });
    }

    public SchemaVersion getSchemaVersion(String subject, int version) {
        return getSchemaVersion(subject, Integer.toString(version));
    }

    public SchemaVersion getLatestSchema(String subject) {
        return getSchemaVersion(subject, LATEST);
    }

    /** Returns only the schema string for a subject/version. */
    public String getRawSchema(String subject, String version) {
        return sendRaw("GET", "/subjects/" + enc(subject) + "/versions/" + enc(version) + "/schema", null);
    }

    /**
     * Registers a schema under a subject. If an identical schema already exists, its id is returned.
     *
     * @return the globally unique schema id
     */
    public int register(String subject, RegisterSchemaRequest request) {
        Map<String, Integer> resp = send("POST", "/subjects/" + enc(subject) + "/versions", request,
                new TypeReference<>() { });
        return resp.get("id");
    }

    /**
     * Looks up whether a schema is registered under a subject.
     *
     * @throws SchemaRegistryException with {@link SchemaRegistryException#SCHEMA_NOT_FOUND} if not registered
     */
    public SchemaVersion lookup(String subject, RegisterSchemaRequest request) {
        return send("POST", "/subjects/" + enc(subject), request, new TypeReference<>() { });
    }

    /** Deletes a version of a subject. Returns the deleted version number. */
    public int deleteSchemaVersion(String subject, String version, boolean permanent) {
        return send("DELETE", "/subjects/" + enc(subject) + "/versions/" + enc(version)
                + new Query().addIf(permanent, "permanent", "true"), null, new TypeReference<Integer>() { });
    }

    /** Lists ids of schemas that reference the given subject/version. */
    public List<Integer> getReferencedBy(String subject, String version) {
        return send("GET", "/subjects/" + enc(subject) + "/versions/" + enc(version) + "/referencedby",
                null, new TypeReference<>() { });
    }

    // ------------------------------------------------------------------ schemas

    /** Fetches a schema by its global id (cached). */
    public Schema getSchemaById(int id) {
        Schema cached = schemaByIdCache.get(id);
        if (cached != null) {
            return cached;
        }
        Schema schema = send("GET", "/schemas/ids/" + id, null, new TypeReference<Schema>() { });
        schemaByIdCache.put(id, schema);
        return schema;
    }

    /** Lists the subject/version pairs a schema id is registered under. */
    public List<SubjectVersion> getSubjectVersionsById(int id) {
        return send("GET", "/schemas/ids/" + id + "/versions", null, new TypeReference<>() { });
    }

    /** Lists the schema types supported by the registry. */
    public List<String> getSchemaTypes() {
        return send("GET", "/schemas/types", null, new TypeReference<>() { });
    }

    // ------------------------------------------------------------------ compatibility

    /** Tests a schema against a specific version (or {@code "latest"}) of a subject. */
    public CompatibilityResult testCompatibility(String subject, String version, RegisterSchemaRequest request,
                                                 boolean verbose) {
        return send("POST", "/compatibility/subjects/" + enc(subject) + "/versions/" + enc(version)
                + new Query().addIf(verbose, "verbose", "true"), request, new TypeReference<>() { });
    }

    /** Tests a schema against all versions of a subject (as defined by the subject's compatibility level). */
    public CompatibilityResult testCompatibility(String subject, RegisterSchemaRequest request, boolean verbose) {
        return send("POST", "/compatibility/subjects/" + enc(subject) + "/versions"
                + new Query().addIf(verbose, "verbose", "true"), request, new TypeReference<>() { });
    }

    // ------------------------------------------------------------------ config

    /** Returns the global compatibility level. */
    public CompatibilityLevel getGlobalCompatibility() {
        return readCompatibility(send("GET", "/config", null, new TypeReference<Map<String, String>>() { }));
    }

    public CompatibilityLevel setGlobalCompatibility(CompatibilityLevel level) {
        return readCompatibility(send("PUT", "/config", Map.of("compatibility", level.name()),
                new TypeReference<Map<String, String>>() { }));
    }

    /**
     * Returns the compatibility level for a subject.
     *
     * @param defaultToGlobal when {@code true}, falls back to the global level if the subject has none
     */
    public CompatibilityLevel getSubjectCompatibility(String subject, boolean defaultToGlobal) {
        return readCompatibility(send("GET", "/config/" + enc(subject)
                        + new Query().addIf(defaultToGlobal, "defaultToGlobal", "true"),
                null, new TypeReference<Map<String, String>>() { }));
    }

    public CompatibilityLevel setSubjectCompatibility(String subject, CompatibilityLevel level) {
        return readCompatibility(send("PUT", "/config/" + enc(subject), Map.of("compatibility", level.name()),
                new TypeReference<Map<String, String>>() { }));
    }

    /** Removes the subject-level compatibility override, returning the level that was removed. */
    public CompatibilityLevel deleteSubjectCompatibility(String subject) {
        return readCompatibility(send("DELETE", "/config/" + enc(subject), null,
                new TypeReference<Map<String, String>>() { }));
    }

    // ------------------------------------------------------------------ mode

    public Mode getGlobalMode() {
        return readMode(send("GET", "/mode", null, new TypeReference<Map<String, String>>() { }));
    }

    public Mode setGlobalMode(Mode mode) {
        return readMode(send("PUT", "/mode", Map.of("mode", mode.name()), new TypeReference<Map<String, String>>() { }));
    }

    public Mode getSubjectMode(String subject, boolean defaultToGlobal) {
        return readMode(send("GET", "/mode/" + enc(subject)
                        + new Query().addIf(defaultToGlobal, "defaultToGlobal", "true"),
                null, new TypeReference<Map<String, String>>() { }));
    }

    public Mode setSubjectMode(String subject, Mode mode) {
        return readMode(send("PUT", "/mode/" + enc(subject), Map.of("mode", mode.name()),
                new TypeReference<Map<String, String>>() { }));
    }

    public Mode deleteSubjectMode(String subject) {
        return readMode(send("DELETE", "/mode/" + enc(subject), null, new TypeReference<Map<String, String>>() { }));
    }

    // ------------------------------------------------------------------ internals

    private static CompatibilityLevel readCompatibility(Map<String, String> body) {
        // GET returns "compatibilityLevel"; PUT echoes "compatibility".
        String value = body.getOrDefault("compatibilityLevel", body.get("compatibility"));
        if (value == null) {
            throw new SchemaRegistryException(200, -1, "Response did not contain a compatibility level: " + body);
        }
        return CompatibilityLevel.parse(value);
    }

    private static Mode readMode(Map<String, String> body) {
        String value = body.get("mode");
        if (value == null) {
            throw new SchemaRegistryException(200, -1, "Response did not contain a mode: " + body);
        }
        return Mode.parse(value);
    }

    private <T> T send(String method, String path, Object body, TypeReference<T> type) {
        String raw = sendRaw(method, path, body);
        try {
            return MAPPER.readValue(raw, type);
        } catch (JsonProcessingException e) {
            throw new SchemaRegistryException("Could not parse response from " + method + " " + path + ": "
                    + e.getOriginalMessage(), e);
        }
    }

    private String sendRaw(String method, String path, Object body) {
        HttpRequest request = buildRequest(method, path, body);
        SchemaRegistryException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                sleep(retryBackoff.multipliedBy(1L << Math.min(attempt - 1, 10)));
            }
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                last = new SchemaRegistryException("Failed to reach schema registry at " + baseUri + ": "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SchemaRegistryException("Interrupted while calling schema registry", e);
            }
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return response.body();
            }
            last = toException(status, response.body());
            if (status < 500 && status != 429) {
                break; // client errors are not retryable
            }
        }
        throw last;
    }

    private HttpRequest buildRequest(String method, String path, Object body) {
        HttpRequest.BodyPublisher publisher;
        if (body == null) {
            publisher = HttpRequest.BodyPublishers.noBody();
        } else {
            try {
                publisher = HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Could not serialize request body", e);
            }
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUri + path))
                .timeout(requestTimeout)
                .header("Accept", CONTENT_TYPE + ", application/json")
                .method(method, publisher);
        if (body != null) {
            rb.header("Content-Type", CONTENT_TYPE);
        }
        headers.forEach(rb::header);
        return rb.build();
    }

    private static SchemaRegistryException toException(int status, String body) {
        try {
            ErrorMessage err = MAPPER.readValue(body, ErrorMessage.class);
            if (err.message() != null) {
                return new SchemaRegistryException(status, err.errorCode(), err.message());
            }
        } catch (JsonProcessingException ignored) {
            // fall through to a generic error
        }
        String text = body == null || body.isBlank() ? "HTTP " + status : body.strip();
        return new SchemaRegistryException(status, status, text);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SchemaRegistryException("Interrupted while retrying", e);
        }
    }

    private static String enc(String segment) {
        Objects.requireNonNull(segment, "path segment must not be null");
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Small helper for building query strings. */
    private static final class Query {
        private final List<String> parts = new ArrayList<>();

        Query add(String key, String value) {
            parts.add(enc(key) + "=" + enc(value));
            return this;
        }

        Query addIf(boolean condition, String key, String value) {
            return condition ? add(key, value) : this;
        }

        @Override
        public String toString() {
            if (parts.isEmpty()) {
                return "";
            }
            StringJoiner j = new StringJoiner("&", "?", "");
            parts.forEach(j::add);
            return j.toString();
        }
    }

    /** Builder for {@link SchemaRegistryClient}. */
    public static final class Builder {
        private String baseUrl;
        private HttpClient httpClient;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private final Map<String, String> headers = new LinkedHashMap<>();
        private int maxRetries = 2;
        private Duration retryBackoff = Duration.ofMillis(200);

        private Builder() {
        }

        /** Registry base URL, e.g. {@code http://localhost:8081}. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** HTTP basic authentication (e.g. Confluent Cloud API key/secret). */
        public Builder basicAuth(String username, String password) {
            String token = Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            headers.put("Authorization", "Basic " + token);
            return this;
        }

        /** Bearer token authentication. */
        public Builder bearerToken(String token) {
            headers.put("Authorization", "Bearer " + token);
            return this;
        }

        /** Adds an extra header sent with every request. */
        public Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = Objects.requireNonNull(timeout);
            return this;
        }

        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = Objects.requireNonNull(timeout);
            return this;
        }

        /** Number of retries for transport errors, HTTP 429 and 5xx responses (default 2). */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be >= 0");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /** Initial retry backoff; doubled on each subsequent retry (default 200ms). */
        public Builder retryBackoff(Duration backoff) {
            this.retryBackoff = Objects.requireNonNull(backoff);
            return this;
        }

        /** Supplies a custom {@link HttpClient} (e.g. for TLS or proxy settings). */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public SchemaRegistryClient build() {
            return new SchemaRegistryClient(this);
        }
    }
}
