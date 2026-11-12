package com.sanjaymu.schemaregistry.cli;

import com.sanjaymu.schemaregistry.local.LocalSchemaRegistryServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import javax.tools.ToolProvider;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeCommandsTest {

    public record Order(long id, String customer, BigDecimal amount) {
    }

    public record OrderWithCoupon(long id, String customer, BigDecimal amount, String coupon) {
    }

    public record BadOrder(long id, int amount) {
    }

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

    private static String cls(Class<?> c) {
        return c.getName();
    }

    @Test
    void generateRegisterAndCheck() {
        Result gen = run("schema", "generate", "--class", cls(Order.class));
        assertEquals(0, gen.exitCode(), gen.err());
        assertTrue(gen.out().contains("\"required\" : [ \"id\" ]"), gen.out());

        Result reg = run("schema", "generate", "-c", cls(Order.class), "--register", "orders-value");
        assertEquals("Registered schema for " + cls(Order.class) + " under 'orders-value' as version 1 (id 1)", reg.out());
        assertEquals(0, run("schema", "generate", "-c", cls(OrderWithCoupon.class), "--register", "orders-value").exitCode());

        Result ok = run("schema", "check-type", "orders-value", "-c", cls(OrderWithCoupon.class));
        assertEquals(0, ok.exitCode(), ok.out());
        assertTrue(ok.out().contains("orders-value v1 (id 1): OK (with warnings)"), ok.out());
        assertTrue(ok.out().contains("orders-value v2 (id 2): OK"), ok.out());

        assertEquals(TypeCommands.TYPE_MISMATCH,
                run("schema", "check-type", "orders-value", "-c", cls(OrderWithCoupon.class), "--strict").exitCode());

        Result bad = run("schema", "check-type", "orders-value", "-c", cls(BadOrder.class), "-v", "latest");
        assertEquals(TypeCommands.TYPE_MISMATCH, bad.exitCode());
        assertTrue(bad.out().contains("MISMATCH"), bad.out());
        assertTrue(bad.out().contains("$.amount: schema type 'number' allows decimals"), bad.out());

        Result json = run("-o", "json", "schema", "check-type", "orders-value", "-c", cls(Order.class), "-v", "1");
        assertTrue(json.out().contains("\"matches\" : true"), json.out());
    }

    @Test
    void loadsClassesFromAClasspathDirectory() throws Exception {
        Path src = tmp.resolve("src/com/example/Shipment.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package com.example;
                public record Shipment(String id, double weightKg, boolean express) {}
                """);
        Path classes = tmp.resolve("classes");
        Files.createDirectories(classes);
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), src.toString());
        assertEquals(0, rc, "javac failed");

        Result missing = run("schema", "generate", "-c", "com.example.Shipment");
        assertEquals(1, missing.exitCode());
        assertTrue(missing.err().contains("add --classpath"), missing.err());

        Path out = tmp.resolve("shipment.schema.json");
        Result gen = run("schema", "generate", "-c", "com.example.Shipment", "--cp", classes.toString(),
                "--out", out.toString(), "--register", "shipments-value");
        assertEquals(0, gen.exitCode(), gen.err());
        assertTrue(Files.readString(out).contains("\"weightKg\""));
        assertEquals(0, run("schema", "check-type", "shipments-value", "-c", "com.example.Shipment",
                "--cp", classes.toString()).exitCode());
    }
}
