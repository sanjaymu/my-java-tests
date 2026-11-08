package com.sanjaymu.schemaregistry.cli;

import com.sanjaymu.schemaregistry.local.LocalSchemaRegistryServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadCommandTest {

    static final String SCHEMA = """
            {"type":"object","properties":{"id":{"type":"integer"},"customer":{"type":"string"}},
             "required":["id","customer"]}""";

    record Result(int exitCode, String out, String err) {
    }

    @TempDir
    Path tmp;

    private LocalSchemaRegistryServer server;

    @BeforeEach
    void start() throws Exception {
        server = LocalSchemaRegistryServer.start(0);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private Result run(String... args) {
        List<String> all = new ArrayList<>(List.of("--url", server.url()));
        all.addAll(List.of(args));
        CommandLine cmd = SrCtl.commandLine();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return new Result(cmd.execute(all.toArray(String[]::new)), out.toString().strip(), err.toString().strip());
    }

    @Test
    void validateEncodeDecode() throws Exception {
        assertEquals(0, run("schema", "register", "orders-value", "-t", "JSON", "-s", SCHEMA).exitCode());

        Result valid = run("payload", "validate", "orders-value", "-d", "{\"id\":1,\"customer\":\"acme\"}");
        assertEquals(0, valid.exitCode(), valid.err());
        assertEquals("VALID against orders-value v1 (id 1)", valid.out());

        Result invalid = run("payload", "validate", "orders-value", "-d", "{\"id\":\"x\"}");
        assertEquals(4, invalid.exitCode());
        assertTrue(invalid.out().startsWith("INVALID"), invalid.out());
        assertTrue(invalid.out().contains("customer"), invalid.out());

        Path payload = tmp.resolve("order.json");
        Files.writeString(payload, "{\"id\":7,\"customer\":\"globex\"}");
        Result encoded = run("payload", "encode", "orders-value", "-p", payload.toString());
        assertEquals(0, encoded.exitCode(), encoded.err());

        Result decoded = run("payload", "decode", "--base64", encoded.out(), "--subject", "orders-value");
        assertEquals(0, decoded.exitCode(), decoded.err());
        assertTrue(decoded.out().startsWith("Schema ID: 1 (orders-value v1)"), decoded.out());
        assertTrue(decoded.out().contains("\"globex\""));

        Path bin = tmp.resolve("msg.bin");
        assertEquals(0, run("payload", "encode", "orders-value", "-p", payload.toString(), "--out", bin.toString()).exitCode());
        Result json = run("-o", "json", "payload", "decode", "-i", bin.toString());
        assertTrue(json.out().contains("\"schemaId\" : 1"), json.out());

        assertEquals(4, run("payload", "encode", "orders-value", "-d", "{}").exitCode());
        Result wrongSubject = run("payload", "decode", "-i", bin.toString(), "--subject", "other");
        assertEquals(1, wrongSubject.exitCode());
        assertTrue(wrongSubject.err().contains("not registered under subject 'other'"), wrongSubject.err());
    }

    @Test
    void rejectsNonJsonSubjects() {
        assertEquals(0, run("schema", "register", "avro-value", "-s", "\"string\"").exitCode());
        Result r = run("payload", "validate", "avro-value", "-d", "\"x\"");
        assertEquals(1, r.exitCode());
        assertTrue(r.err().contains("JSON schemas only"), r.err());
    }
}
