package com.sanjaymu.schemaregistry.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.sanjaymu.schemaregistry.client.model.SchemaType;
import com.sanjaymu.schemaregistry.client.model.SchemaVersion;
import com.sanjaymu.schemaregistry.serde.DeserializedMessage;
import com.sanjaymu.schemaregistry.serde.JsonSchemaDeserializer;
import com.sanjaymu.schemaregistry.serde.JsonSchemaSerializer;
import com.sanjaymu.schemaregistry.serde.JsonSchemaValidator;
import com.sanjaymu.schemaregistry.serde.SerdeException;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Command(name = "payload", description = "Validate, encode and decode JSON payloads against registered JSON schemas.",
        mixinStandardHelpOptions = true,
        subcommands = {PayloadCommand.ValidateCmd.class, PayloadCommand.EncodeCmd.class, PayloadCommand.DecodeCmd.class})
final class PayloadCommand {

    /** Exit code when a payload fails schema validation. */
    static final int INVALID = 4;

    static final class JsonInput {
        @Option(names = {"-p", "--payload"}, required = true, paramLabel = "PATH",
                description = "JSON payload file ('-' for stdin).")
        Path file;

        @Option(names = {"-d", "--data"}, required = true, paramLabel = "JSON", description = "Inline JSON payload.")
        String inline;

        String read() throws IOException {
            if (inline != null) {
                return inline;
            }
            return "-".equals(file.toString())
                    ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                    : Files.readString(file);
        }
    }

    static int reportInvalid(BaseCommand cmd, List<String> errors, String context) {
        if (cmd.jsonOutput()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("valid", false);
            m.put("schema", context);
            m.put("errors", errors);
            cmd.printJson(m);
        } else {
            cmd.out().println("INVALID against " + context);
            errors.forEach(e -> cmd.out().println("  - " + e));
        }
        return INVALID;
    }

    @Command(name = "validate", description = "Validate a JSON payload against a subject version. Exits with 4 when invalid.",
            mixinStandardHelpOptions = true)
    static final class ValidateCmd extends BaseCommand {
        @Parameters(index = "0", description = "Subject name.")
        String subject;

        @ArgGroup(exclusive = true, multiplicity = "1")
        JsonInput input;

        @Option(names = {"-v", "--version"}, defaultValue = "latest", description = "Version (default: ${DEFAULT-VALUE}).")
        String version;

        @Override
        int run() throws Exception {
            SchemaVersion sv = client().getSchemaVersion(subject, version);
            if (sv.type() != SchemaType.JSON) {
                throw new IllegalArgumentException("Subject '" + subject + "' version " + sv.version() + " is "
                        + sv.type() + "; payload validation supports JSON schemas only");
            }
            JsonNode payload;
            try {
                payload = JSON.readTree(input.read());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("Payload is not valid JSON: " + e.getOriginalMessage());
            }
            String context = subject + " v" + sv.version() + " (id " + sv.id() + ")";
            List<String> errors = JsonSchemaValidator.compile(sv.schema()).validate(payload);
            if (!errors.isEmpty()) {
                return reportInvalid(this, errors, context);
            }
            print(Map.of("valid", true, "schema", context), "VALID against " + context);
            return 0;
        }
    }

    @Command(name = "encode", description = "Validate a JSON payload and frame it with its schema id (registry wire format).",
            mixinStandardHelpOptions = true)
    static final class EncodeCmd extends BaseCommand {
        @Parameters(index = "0", description = "Subject name.")
        String subject;

        @ArgGroup(exclusive = true, multiplicity = "1")
        JsonInput input;

        @Option(names = {"-v", "--version"}, defaultValue = "latest", description = "Version (default: ${DEFAULT-VALUE}).")
        String version;

        @Option(names = "--out", paramLabel = "PATH", description = "Write binary message to a file (default: print base64).")
        Path outFile;

        @Override
        int run() throws Exception {
            JsonSchemaSerializer.Builder b = JsonSchemaSerializer.builder(client()).subject(subject);
            if ("latest".equalsIgnoreCase(version)) {
                b.useLatestVersion();
            } else {
                b.useVersion(Integer.parseInt(version));
            }
            JsonSchemaSerializer serializer = b.build();
            byte[] message;
            try {
                message = serializer.serializeJson(input.read());
            } catch (SerdeException e) {
                if (e.isValidationFailure()) {
                    return reportInvalid(this, e.validationErrors(), subject + " v" + serializer.schemaVersion()
                            + " (id " + serializer.schemaId() + ")");
                }
                throw new IllegalArgumentException(e.getMessage());
            }
            String b64 = Base64.getEncoder().encodeToString(message);
            if (outFile != null) {
                Files.write(outFile, message);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schemaId", serializer.schemaId());
            m.put("subject", subject);
            m.put("version", serializer.schemaVersion());
            m.put("bytes", message.length);
            if (outFile != null) {
                m.put("file", outFile.toString());
            } else {
                m.put("base64", b64);
            }
            print(m, outFile != null
                    ? "Wrote " + message.length + " bytes (schema id " + serializer.schemaId() + ") to " + outFile
                    : b64);
            return 0;
        }
    }

    @Command(name = "decode", description = "Decode a framed message: resolve its schema, validate and print the payload.",
            mixinStandardHelpOptions = true)
    static final class DecodeCmd extends BaseCommand {
        static final class Source {
            @Option(names = {"-i", "--in"}, required = true, paramLabel = "PATH", description = "Binary message file.")
            Path file;

            @Option(names = "--base64", required = true, paramLabel = "B64", description = "Base64-encoded message.")
            String base64;
        }

        @ArgGroup(exclusive = true, multiplicity = "1")
        Source source;

        @Option(names = "--subject", description = "Require the schema to be registered under this subject.")
        String subject;

        @Option(names = "--no-validate", description = "Skip schema validation.")
        boolean noValidate;

        @Override
        int run() throws Exception {
            byte[] message;
            try {
                message = source.file != null ? Files.readAllBytes(source.file)
                        : Base64.getDecoder().decode(source.base64.strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("--base64 value is not valid base64");
            }
            JsonSchemaDeserializer.Builder b = JsonSchemaDeserializer.builder(client()).validate(!noValidate);
            if (subject != null) {
                b.expectedSubject(subject);
            }
            DeserializedMessage<JsonNode> msg;
            try {
                msg = b.build().deserializeWithMetadata(message, JsonNode.class);
            } catch (SerdeException e) {
                if (e.isValidationFailure()) {
                    return reportInvalid(this, e.validationErrors(), "schema id " + com.sanjaymu.schemaregistry.serde
                            .WireFormat.decode(message).schemaId());
                }
                throw new IllegalArgumentException(e.getMessage());
            }
            if (jsonOutput()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("schemaId", msg.schemaId());
                if (msg.subject() != null) {
                    m.put("subject", msg.subject());
                    m.put("version", msg.version());
                }
                m.put("payload", msg.value());
                printJson(m);
            } else {
                out().println("Schema ID: " + msg.schemaId()
                        + (msg.subject() != null ? " (" + msg.subject() + " v" + msg.version() + ")" : ""));
                out().println(JSON.writeValueAsString(msg.value()));
            }
            return 0;
        }
    }
}
