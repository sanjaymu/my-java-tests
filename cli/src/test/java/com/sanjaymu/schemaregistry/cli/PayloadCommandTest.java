package com.sanjaymu.schemaregistry.cli;

import com.sanjaymu.schemaregistry.local.LocalSchemaRegistryServer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadCommandTest {

    public record Order(long id, @NotBlank String customer, @PositiveOrZero BigDecimal amount) {
    }

    public record WrongOrder(String id, @NotBlank String customer) {
    }

    record Result(int exitCode, String out, String err) {
    }

    static final String ORDER = Order.class.getName();

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
    void validateAgainstJavaModel() {
        Result valid = run("payload", "validate", "-c", ORDER, "-d", "{\"id\":1,\"customer\":\"acme\",\"amount\":2.5}");
        assertEquals(0, valid.exitCode(), valid.err());
        assertEquals("VALID against " + ORDER, valid.out());

        Result invalid = run("payload", "validate", "-c", ORDER, "-d", "{\"customer\":\" \",\"amount\":-1}");
        assertEquals(PayloadCommand.INVALID, invalid.exitCode());
        assertTrue(invalid.out().contains("$.id: required property is missing"), invalid.out());

        Result constraints = run("payload", "validate", "-c", ORDER, "-d", "{\"id\":1,\"customer\":\" \",\"amount\":-1}");
        assertTrue(constraints.out().contains("$.amount: must be greater than or equal to 0"), constraints.out());
        assertTrue(constraints.out().contains("$.customer: must not be blank"), constraints.out());

        Result strict = run("payload", "validate", "-c", ORDER, "-d", "{\"id\":\"1\",\"customer\":\"a\"}");
        assertEquals(PayloadCommand.INVALID, strict.exitCode());
        assertTrue(strict.out().contains("$.id:"), strict.out());
    }

    @Test
    void encodeAndDecode() throws Exception {
        Path payload = tmp.resolve("order.json");
        Files.writeString(payload, "{\"id\":7,\"customer\":\"globex\",\"amount\":10.50}");

        Result notRegistered = run("payload", "encode", "orders-value", "-c", ORDER, "-p", payload.toString());
        assertEquals(1, notRegistered.exitCode(), "schema must be registered unless --register");

        Result encoded = run("payload", "encode", "orders-value", "-c", ORDER, "-p", payload.toString(), "--register");
        assertEquals(0, encoded.exitCode(), encoded.err());

        Result decoded = run("payload", "decode", "--base64", encoded.out(), "-c", ORDER, "--subject", "orders-value");
        assertEquals(0, decoded.exitCode(), decoded.err());
        assertTrue(decoded.out().startsWith("Schema ID: 1 (orders-value v1) as " + ORDER), decoded.out());
        assertTrue(decoded.out().contains("\"amount\" : 10.50"), "decimal kept exact: " + decoded.out());

        Result raw = run("payload", "decode", "--base64", encoded.out());
        assertTrue(raw.out().contains("raw JSON"), raw.out());

        Path bin = tmp.resolve("msg.bin");
        assertEquals(0, run("payload", "encode", "orders-value", "-c", ORDER, "-p", payload.toString(),
                "--out", bin.toString()).exitCode());
        Result json = run("-o", "json", "payload", "decode", "-i", bin.toString(), "-c", ORDER);
        assertTrue(json.out().contains("\"schemaId\" : 1"), json.out());

        assertEquals(PayloadCommand.INVALID,
                run("payload", "encode", "orders-value", "-c", ORDER, "-d", "{\"id\":1,\"customer\":\"\"}").exitCode());

        Result mismatch = run("payload", "decode", "-i", bin.toString(), "-c", WrongOrder.class.getName());
        assertEquals(TypeCommands.TYPE_MISMATCH, mismatch.exitCode());
        assertTrue(mismatch.out().contains("$.id: schema type 'integer' cannot be mapped to Java type String"), mismatch.out());

        Result wrongSubject = run("payload", "decode", "-i", bin.toString(), "--subject", "other");
        assertEquals(1, wrongSubject.exitCode());
        assertTrue(wrongSubject.err().contains("not registered under subject 'other'"), wrongSubject.err());
    }
}
