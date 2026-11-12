package com.sanjaymu.schemaregistry.local;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanjaymu.schemaregistry.client.model.CompatibilityLevel;
import com.sanjaymu.schemaregistry.client.model.Mode;
import com.sanjaymu.schemaregistry.client.model.RegisterSchemaRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Embeddable HTTP server exposing an {@link InMemorySchemaRegistry} through the Confluent-compatible REST API.
 *
 * <pre>{@code
 * try (LocalSchemaRegistryServer server = LocalSchemaRegistryServer.start(0)) {
 *     SchemaRegistryClient client = SchemaRegistryClient.create(server.url());
 *     ...
 * }
 * }</pre>
 */
public final class LocalSchemaRegistryServer implements AutoCloseable {

    private static final String CONTENT_TYPE = "application/vnd.schemaregistry.v1+json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor;
    private final InMemorySchemaRegistry registry;

    private LocalSchemaRegistryServer(HttpServer server, ExecutorService executor, InMemorySchemaRegistry registry) {
        this.server = server;
        this.executor = executor;
        this.registry = registry;
    }

    /** Starts a server on {@code localhost:port}; use port {@code 0} for an ephemeral port. */
    public static LocalSchemaRegistryServer start(int port) throws IOException {
        return start("127.0.0.1", port, new InMemorySchemaRegistry());
    }

    public static LocalSchemaRegistryServer start(String host, int port, InMemorySchemaRegistry registry)
            throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(host, port), 0);
        ExecutorService executor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "local-schema-registry");
            t.setDaemon(true);
            return t;
        });
        LocalSchemaRegistryServer s = new LocalSchemaRegistryServer(http, executor, registry);
        http.createContext("/", s::handle);
        http.setExecutor(executor);
        http.start();
        return s;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://" + server.getAddress().getHostString() + ":" + port();
    }

    public InMemorySchemaRegistry registry() {
        return registry;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ request handling

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            Object result;
            try {
                result = route(ex);
            } catch (RegistryError e) {
                writeError(ex, e.httpStatus, e.errorCode, e.getMessage());
                return;
            } catch (BadRequest e) {
                writeError(ex, 400, 400, e.getMessage());
                return;
            } catch (RuntimeException e) {
                writeError(ex, 500, 50001, "Internal error: " + e);
                return;
            }
            if (result instanceof RawText raw) {
                write(ex, 200, raw.text);
            } else {
                write(ex, 200, MAPPER.writeValueAsString(result));
            }
        }
    }

    private record RawText(String text) {
    }

    private static final class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }

    private Object route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        List<String> p = segments(ex.getRequestURI().getRawPath());
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        int n = p.size();
        String first = n > 0 ? p.get(0) : "";

        switch (first) {
            case "" -> {
                if (n == 0 && "GET".equals(method)) {
                    return Map.of();
                }
            }
            case "subjects" -> {
                if (n == 1 && "GET".equals(method)) {
                    return registry.subjects(q.get("subjectPrefix"), flag(q, "deleted"));
                }
                String subject = n > 1 ? p.get(1) : null;
                if (n == 2) {
                    return switch (method) {
                        case "POST" -> registry.lookup(subject, readSchemaRequest(ex));
                        case "DELETE" -> registry.deleteSubject(subject, flag(q, "permanent"));
                        default -> throw notFound(ex);
                    };
                }
                if (n == 3 && "versions".equals(p.get(2))) {
                    return switch (method) {
                        case "GET" -> registry.versions(subject, flag(q, "deleted"));
                        case "POST" -> Map.of("id", registry.register(subject, readSchemaRequest(ex)));
                        default -> throw notFound(ex);
                    };
                }
                if (n == 4 && "versions".equals(p.get(2))) {
                    String version = p.get(3);
                    return switch (method) {
                        case "GET" -> registry.version(subject, version);
                        case "DELETE" -> registry.deleteVersion(subject, version, flag(q, "permanent"));
                        default -> throw notFound(ex);
                    };
                }
                if (n == 5 && "versions".equals(p.get(2)) && "GET".equals(method)) {
                    if ("schema".equals(p.get(4))) {
                        return new RawText(registry.version(subject, p.get(3)).schema());
                    }
                    if ("referencedby".equals(p.get(4))) {
                        return registry.referencedBy(subject, p.get(3));
                    }
                }
            }
            case "schemas" -> {
                if (n == 2 && "types".equals(p.get(1)) && "GET".equals(method)) {
                    return registry.schemaTypes();
                }
                if (n >= 3 && "ids".equals(p.get(1)) && "GET".equals(method)) {
                    int id = parseId(p.get(2));
                    if (n == 3) {
                        return registry.schemaById(id);
                    }
                    if (n == 4 && "versions".equals(p.get(3))) {
                        return registry.subjectVersionsById(id);
                    }
                    if (n == 4 && "schema".equals(p.get(3))) {
                        return new RawText(registry.schemaById(id).schema());
                    }
                }
            }
            case "compatibility" -> {
                if ("POST".equals(method) && n >= 4 && "subjects".equals(p.get(1)) && "versions".equals(p.get(3))) {
                    String version = n == 5 ? p.get(4) : null;
                    if (n == 4 || n == 5) {
                        return registry.testCompatibility(p.get(2), version, readSchemaRequest(ex), flag(q, "verbose"));
                    }
                }
            }
            case "config" -> {
                if (n == 1) {
                    switch (method) {
                        case "GET":
                            return Map.of("compatibilityLevel", registry.globalCompatibility().name());
                        case "PUT":
                            CompatibilityLevel level = readCompatibility(ex);
                            registry.setGlobalCompatibility(level);
                            return Map.of("compatibility", level.name());
                        default:
                            break;
                    }
                } else if (n == 2) {
                    String subject = p.get(1);
                    switch (method) {
                        case "GET":
                            return Map.of("compatibilityLevel",
                                    registry.subjectCompatibility(subject, flag(q, "defaultToGlobal")).name());
                        case "PUT":
                            CompatibilityLevel level = readCompatibility(ex);
                            registry.setSubjectCompatibility(subject, level);
                            return Map.of("compatibility", level.name());
                        case "DELETE":
                            return Map.of("compatibilityLevel", registry.deleteSubjectCompatibility(subject).name());
                        default:
                            break;
                    }
                }
            }
            case "mode" -> {
                if (n == 1) {
                    switch (method) {
                        case "GET":
                            return Map.of("mode", registry.globalMode().name());
                        case "PUT":
                            Mode mode = readMode(ex);
                            registry.setGlobalMode(mode);
                            return Map.of("mode", mode.name());
                        default:
                            break;
                    }
                } else if (n == 2) {
                    String subject = p.get(1);
                    switch (method) {
                        case "GET":
                            return Map.of("mode", registry.subjectMode(subject, flag(q, "defaultToGlobal")).name());
                        case "PUT":
                            Mode mode = readMode(ex);
                            registry.setSubjectMode(subject, mode);
                            return Map.of("mode", mode.name());
                        case "DELETE":
                            return Map.of("mode", registry.deleteSubjectMode(subject).name());
                        default:
                            break;
                    }
                }
            }
            default -> {
                // fall through
            }
        }
        throw notFound(ex);
    }

    private static RegistryError notFound(HttpExchange ex) {
        return new RegistryError(404, 404, "HTTP 404 Not Found: " + ex.getRequestMethod() + " " + ex.getRequestURI());
    }

    private static int parseId(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw RegistryError.schemaNotFound();
        }
    }

    private static boolean flag(Map<String, String> q, String key) {
        return "true".equalsIgnoreCase(q.get(key));
    }

    private static RegisterSchemaRequest readSchemaRequest(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        if (!body.hasNonNull("schema") || !body.get("schema").isTextual()) {
            throw RegistryError.invalidSchema("request body must contain a 'schema' string");
        }
        try {
            return MAPPER.treeToValue(body, RegisterSchemaRequest.class);
        } catch (JsonProcessingException e) {
            throw RegistryError.invalidSchema(e.getOriginalMessage());
        }
    }

    private static CompatibilityLevel readCompatibility(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        try {
            return CompatibilityLevel.parse(body.path("compatibility").asText());
        } catch (IllegalArgumentException e) {
            throw new RegistryError(422, 42203, "Invalid compatibility level: " + body.path("compatibility").asText());
        }
    }

    private static Mode readMode(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        try {
            return Mode.parse(body.path("mode").asText());
        } catch (IllegalArgumentException e) {
            throw new RegistryError(422, 42204, "Invalid mode: " + body.path("mode").asText());
        }
    }

    private static JsonNode readJson(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) {
                throw new BadRequest("Request body is required");
            }
            JsonNode node = MAPPER.readTree(bytes);
            if (node == null || !node.isObject()) {
                throw new BadRequest("Request body must be a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new BadRequest("Malformed JSON: " + e.getOriginalMessage());
        }
    }

    private static List<String> segments(String rawPath) {
        List<String> out = new ArrayList<>();
        if (rawPath == null) {
            return out;
        }
        for (String s : rawPath.split("/")) {
            if (!s.isEmpty()) {
                out.add(URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "true" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private static void writeError(HttpExchange ex, int status, int code, String message) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("error_code", code);
        body.put("message", message);
        write(ex, status, body.toString());
    }

    private static void write(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /** Runs a standalone local registry: {@code java ... LocalSchemaRegistryServer [port]}. */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        LocalSchemaRegistryServer server = start("0.0.0.0", port, new InMemorySchemaRegistry());
        System.out.println("Local schema registry listening on http://localhost:" + server.port());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        Thread.currentThread().join();
    }
}
