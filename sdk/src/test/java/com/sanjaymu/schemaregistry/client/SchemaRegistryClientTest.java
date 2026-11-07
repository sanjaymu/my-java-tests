package com.sanjaymu.schemaregistry.client;

import com.sanjaymu.schemaregistry.client.model.CompatibilityLevel;
import com.sanjaymu.schemaregistry.client.model.RegisterSchemaRequest;
import com.sanjaymu.schemaregistry.client.model.Schema;
import com.sanjaymu.schemaregistry.client.model.SchemaVersion;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests against a stub HTTP server that records requests and replays canned responses. */
class SchemaRegistryClientTest {

    record Recorded(String method, String rawPath, String query, String auth, String contentType, String body) {
    }

    record Canned(int status, String body) {
    }

    private HttpServer server;
    private final Queue<Recorded> requests = new ConcurrentLinkedQueue<>();
    private final Queue<Canned> responses = new ConcurrentLinkedQueue<>();
    private SchemaRegistryClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = SchemaRegistryClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/")
                .basicAuth("key", "secret")
                .retryBackoff(Duration.ofMillis(1))
                .build();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Recorded(ex.getRequestMethod(), ex.getRequestURI().getRawPath(),
                    ex.getRequestURI().getRawQuery(), ex.getRequestHeaders().getFirst("Authorization"),
                    ex.getRequestHeaders().getFirst("Content-Type"), body));
            Canned c = responses.poll();
            if (c == null) {
                c = new Canned(500, "{\"error_code\":50001,\"message\":\"no canned response\"}");
            }
            byte[] bytes = c.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(c.status(), bytes.length);
            ex.getResponseBody().write(bytes);
        }
    }

    private void respond(int status, String body) {
        responses.add(new Canned(status, body));
    }

    @Test
    void listsSubjectsWithAuthAndQuery() {
        respond(200, "[\"a\",\"b\"]");
        assertEquals(List.of("a", "b"), client.getSubjects("a", true));
        Recorded r = requests.poll();
        assertEquals("GET", r.method());
        assertEquals("/subjects", r.rawPath());
        assertEquals("subjectPrefix=a&deleted=true", r.query());
        assertEquals("Basic a2V5OnNlY3JldA==", r.auth());
    }

    @Test
    void registerSendsBodyAndEncodesSubject() {
        respond(200, "{\"id\":42}");
        int id = client.register("my topic/value", RegisterSchemaRequest.avro("\"string\""));
        assertEquals(42, id);
        Recorded r = requests.poll();
        assertEquals("POST", r.method());
        assertEquals("/subjects/my%20topic%2Fvalue/versions", r.rawPath());
        assertEquals(SchemaRegistryClient.CONTENT_TYPE, r.contentType());
        // AVRO is the default and is omitted on the wire, like the reference implementation.
        assertEquals("{\"schema\":\"\\\"string\\\"\"}", r.body());
    }

    @Test
    void parsesSchemaVersion() {
        respond(200, "{\"subject\":\"s\",\"id\":7,\"version\":3,\"schemaType\":\"JSON\",\"schema\":\"{}\",\"extra\":1}");
        SchemaVersion v = client.getLatestSchema("s");
        assertEquals("s", v.subject());
        assertEquals(7, v.id());
        assertEquals(3, v.version());
        assertEquals("JSON", v.schemaType());
        assertEquals("/subjects/s/versions/latest", requests.poll().rawPath());
    }

    @Test
    void mapsErrorResponses() {
        respond(404, "{\"error_code\":40401,\"message\":\"Subject 'x' not found.\"}");
        SchemaRegistryException e = assertThrows(SchemaRegistryException.class, () -> client.getVersions("x"));
        assertEquals(404, e.httpStatus());
        assertEquals(SchemaRegistryException.SUBJECT_NOT_FOUND, e.errorCode());
        assertTrue(e.isNotFound());
        assertEquals(1, requests.size(), "4xx responses must not be retried");
    }

    @Test
    void retriesServerErrors() {
        respond(503, "unavailable");
        respond(200, "[\"AVRO\"]");
        assertEquals(List.of("AVRO"), client.getSchemaTypes());
        assertEquals(2, requests.size());
    }

    @Test
    void givesUpAfterMaxRetries() {
        AtomicInteger ignored = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            respond(500, "boom");
            ignored.incrementAndGet();
        }
        SchemaRegistryException e = assertThrows(SchemaRegistryException.class, () -> client.getSchemaTypes());
        assertEquals(500, e.httpStatus());
        assertEquals("boom", e.getMessage());
        assertEquals(3, requests.size());
    }

    @Test
    void cachesSchemasById() {
        respond(200, "{\"schema\":\"\\\"int\\\"\"}");
        Schema first = client.getSchemaById(5);
        Schema second = client.getSchemaById(5);
        assertEquals(first, second);
        assertEquals(1, requests.size());
    }

    @Test
    void readsAndWritesCompatibility() {
        respond(200, "{\"compatibilityLevel\":\"FULL\"}");
        respond(200, "{\"compatibility\":\"NONE\"}");
        assertEquals(CompatibilityLevel.FULL, client.getSubjectCompatibility("s", true));
        assertEquals(CompatibilityLevel.NONE, client.setGlobalCompatibility(CompatibilityLevel.NONE));
        Recorded get = requests.poll();
        assertEquals("/config/s", get.rawPath());
        assertEquals("defaultToGlobal=true", get.query());
        Recorded put = requests.poll();
        assertEquals("PUT", put.method());
        assertEquals("{\"compatibility\":\"NONE\"}", put.body());
    }

    @Test
    void reportsUnreachableRegistry() {
        SchemaRegistryClient dead = SchemaRegistryClient.builder()
                .baseUrl("http://127.0.0.1:1")
                .maxRetries(0)
                .build();
        SchemaRegistryException e = assertThrows(SchemaRegistryException.class, dead::getSubjects);
        assertEquals(-1, e.httpStatus());
        assertTrue(e.getMessage().startsWith("Failed to reach schema registry"));
    }
}
