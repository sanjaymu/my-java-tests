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

class SrCtlTest {

    static final String V1 = """
            {"type":"record","name":"Order","fields":[{"name":"id","type":"long"}]}""";
    static final String V2 = """
            {"type":"record","name":"Order","fields":[{"name":"id","type":"long"},
              {"name":"note","type":["null","string"],"default":null}]}""";
    static final String BAD = """
            {"type":"record","name":"Order","fields":[{"name":"id","type":"long"},{"name":"qty","type":"int"}]}""";

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
        int rc = cmd.execute(all.toArray(String[]::new));
        return new Result(rc, out.toString().strip(), err.toString().strip());
    }

    private Path file(String name, String content) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void endToEndWorkflow() throws Exception {
        Path v1 = file("v1.avsc", V1);
        Path v2 = file("v2.avsc", V2);
        Path bad = file("bad.avsc", BAD);

        assertEquals("(no subjects)", run("subjects", "list").out());

        Result reg = run("schema", "register", "orders-value", "--file", v1.toString());
        assertEquals(0, reg.exitCode(), reg.err());
        assertEquals("Registered schema under 'orders-value' with id 1", reg.out());

        assertEquals("orders-value", run("subjects", "list").out());

        Result incompatible = run("compat", "check", "orders-value", "-f", bad.toString(), "--verbose");
        assertEquals(3, incompatible.exitCode());
        assertTrue(incompatible.out().startsWith("INCOMPATIBLE"), incompatible.out());
        assertTrue(incompatible.out().contains("qty"));

        Result compatible = run("compat", "check", "orders-value", "-f", v2.toString());
        assertEquals(0, compatible.exitCode());
        assertEquals("COMPATIBLE", compatible.out());

        Result rejected = run("schema", "register", "orders-value", "-f", bad.toString());
        assertEquals(1, rejected.exitCode());
        assertTrue(rejected.err().contains("HTTP 409"), rejected.err());

        assertEquals(0, run("schema", "register", "orders-value", "-f", v2.toString()).exitCode());
        assertEquals("1" + System.lineSeparator() + "2", run("subjects", "versions", "orders-value").out());

        Result get = run("schema", "get", "--subject", "orders-value");
        assertTrue(get.out().contains("Version: 2"), get.out());
        assertTrue(get.out().contains("\"note\""));

        Result byId = run("schema", "get", "--id", "1", "--raw");
        assertEquals(V1.replace("\n", "").replace(" ", ""), byId.out().replace(" ", ""));

        Result json = run("-o", "json", "schema", "get", "--subject", "orders-value", "-v", "1");
        assertTrue(json.out().contains("\"version\" : 1"), json.out());

        assertEquals(0, run("schema", "lookup", "orders-value", "-f", v1.toString()).exitCode());
        assertEquals(2, run("schema", "lookup", "orders-value", "-s", "\"string\"").exitCode());

        assertEquals("Soft-deleted 'orders-value' version 2", run("schema", "delete", "orders-value", "latest").out());
        assertEquals(0, run("subjects", "delete", "orders-value").exitCode());
        assertEquals(0, run("subjects", "delete", "orders-value", "--permanent").exitCode());
        assertEquals("(no subjects)", run("subjects", "list", "--deleted").out());
    }

    @Test
    void configAndMode() {
        assertEquals("BACKWARD", run("config", "get").out());
        assertEquals("Compatibility for 'orders' set to FULL", run("config", "set", "full", "--subject", "orders").out());
        assertEquals("FULL", run("config", "get", "orders").out());
        assertEquals(1, run("config", "get", "other", "--no-default-to-global").exitCode());
        assertEquals(0, run("config", "delete", "orders").exitCode());

        assertEquals("READWRITE", run("mode", "get").out());
        assertEquals(0, run("mode", "set", "READONLY", "--subject", "orders").exitCode());
        Result blocked = run("schema", "register", "orders", "-s", "\"string\"");
        assertEquals(1, blocked.exitCode());
        assertTrue(blocked.err().contains("read-only"), blocked.err());
    }

    @Test
    void referencesTypesAndErrors() {
        assertEquals(0, run("schema", "register", "address", "-s",
                "{\"type\":\"record\",\"name\":\"Address\",\"fields\":[{\"name\":\"city\",\"type\":\"string\"}]}").exitCode());
        Result withRef = run("schema", "register", "customer", "-s",
                "{\"type\":\"record\",\"name\":\"Customer\",\"fields\":[{\"name\":\"a\",\"type\":\"Address\"}]}",
                "--ref", "Address=address:1");
        assertEquals(0, withRef.exitCode(), withRef.err());
        assertEquals("2", run("schema", "referenced-by", "address", "1").out());

        assertTrue(run("schema", "types").out().contains("PROTOBUF"));

        Result badRef = run("schema", "register", "x", "-s", "\"string\"", "--ref", "nope");
        assertEquals(1, badRef.exitCode());
        assertTrue(badRef.err().contains("expected name=subject:version"));

        Result missing = run("subjects", "versions", "does-not-exist");
        assertEquals(1, missing.exitCode());
        assertTrue(missing.err().contains("40401"), missing.err());

        Result noSchema = run("schema", "register", "x");
        assertEquals(1, noSchema.exitCode());
        assertTrue(noSchema.err().contains("--file"), noSchema.err());
    }

    @Test
    void helpAndUsageErrors() {
        Result help = run("--help");
        assertEquals(0, help.exitCode());
        assertTrue(help.out().contains("subjects"));
        assertEquals(2, run("schema", "get").exitCode(), "missing --id/--subject is a usage error");
    }
}
