package com.sanjaymu.schemaregistry.cli;

import com.sanjaymu.schemaregistry.client.SchemaRegistryClient;
import com.sanjaymu.schemaregistry.client.SchemaRegistryException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintWriter;
import java.time.Duration;

/** Entry point for the {@code srctl} command-line tool. */
@Command(name = "srctl",
        mixinStandardHelpOptions = true,
        version = "srctl 1.0.0",
        description = "Command-line access to a Confluent-compatible Schema Registry.",
        footer = {"",
                "Connection settings can also be provided through environment variables:",
                "  SR_URL, SR_USERNAME, SR_PASSWORD, SR_TOKEN",
                "",
                "Examples:",
                "  srctl subjects list",
                "  srctl schema register orders-value --file order.avsc",
                "  srctl compat check orders-value --file order-v2.avsc --verbose",
                "  srctl payload validate orders-value --payload order.json",
                "  srctl local-server --port 8081"},
        subcommands = {
                SubjectsCommand.class,
                SchemaCommand.class,
                CompatCommand.class,
                ConfigCommand.class,
                ModeCommand.class,
                PayloadCommand.class,
                LocalServerCommand.class,
                CommandLine.HelpCommand.class
        })
public final class SrCtl {

    @Option(names = {"-u", "--url"}, defaultValue = "${env:SR_URL:-http://localhost:8081}",
            description = "Schema registry URL (default: ${DEFAULT-VALUE}).")
    String url;

    @Option(names = "--username", defaultValue = "${env:SR_USERNAME}",
            description = "Username / API key for basic auth.")
    String username;

    @Option(names = "--password", defaultValue = "${env:SR_PASSWORD}", interactive = false,
            description = "Password / API secret for basic auth.")
    String password;

    @Option(names = "--token", defaultValue = "${env:SR_TOKEN}",
            description = "Bearer token for authentication.")
    String token;

    @Option(names = "--timeout", defaultValue = "30",
            description = "Request timeout in seconds (default: ${DEFAULT-VALUE}).")
    int timeoutSeconds;

    @Option(names = {"-o", "--output"}, defaultValue = "text",
            description = "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
    OutputFormat output;

    @Option(names = "--debug", description = "Print stack traces on errors.")
    boolean debug;

    private SchemaRegistryClient client;

    /** Lazily builds the SDK client from the global options. */
    SchemaRegistryClient client() {
        if (client == null) {
            SchemaRegistryClient.Builder b = SchemaRegistryClient.builder()
                    .baseUrl(url)
                    .requestTimeout(Duration.ofSeconds(timeoutSeconds));
            if (token != null && !token.isBlank()) {
                b.bearerToken(token);
            } else if (username != null && !username.isBlank()) {
                b.basicAuth(username, password == null ? "" : password);
            }
            client = b.build();
        }
        return client;
    }

    /** Builds a configured {@link CommandLine} (also used by tests). */
    public static CommandLine commandLine() {
        CommandLine cmd = new CommandLine(new SrCtl());
        cmd.setCaseInsensitiveEnumValuesAllowed(true);
        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            PrintWriter err = commandLine.getErr();
            SrCtl root = commandLine.getCommandSpec().root().userObject() instanceof SrCtl r ? r : null;
            if (ex instanceof SchemaRegistryException sre) {
                if (sre.httpStatus() > 0) {
                    err.printf("Error (HTTP %d, code %d): %s%n", sre.httpStatus(), sre.errorCode(), sre.getMessage());
                } else {
                    err.println("Error: " + sre.getMessage());
                }
            } else if (ex instanceof IllegalArgumentException || ex instanceof java.io.IOException) {
                err.println("Error: " + ex.getMessage());
            } else {
                err.println("Error: " + ex);
            }
            if (root != null && root.debug) {
                ex.printStackTrace(err);
            }
            err.flush();
            return 1;
        });
        return cmd;
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }
}
