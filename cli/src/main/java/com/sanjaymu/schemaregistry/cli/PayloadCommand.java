package com.sanjaymu.schemaregistry.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanjaymu.schemaregistry.serde.DeserializedMessage;
import com.sanjaymu.schemaregistry.serde.JavaContract;
import com.sanjaymu.schemaregistry.serde.JsonSchemaDeserializer;
import com.sanjaymu.schemaregistry.serde.JsonSchemaSerializer;
import com.sanjaymu.schemaregistry.serde.SchemaTypeMismatchException;
import com.sanjaymu.schemaregistry.serde.SerdeException;
import com.sanjaymu.schemaregistry.serde.SerdeMappers;
import com.sanjaymu.schemaregistry.serde.WireFormat;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Command(name = "payload",
        description = "Validate, encode and decode JSON payloads, enforcing the schema through a Java model class.",
        mixinStandardHelpOptions = true,
        subcommands = {PayloadCommand.ValidateCmd.class, PayloadCommand.EncodeCmd.class, PayloadCommand.DecodeCmd.class})
final class PayloadCommand {

    /** Exit code when a payload violates the Java model's contract. */
    static final int INVALID = 4;

    static final ObjectMapper MAPPER = SerdeMappers.defaultMapper();

    static final class JsonInput {
        @Option(names = {"-p", "--payload"}, required = true, paramLabel = "PATH",
                description = "JSON payload file ('-' for stdin).")
        Path file;

        @Option(names = {"-d", "--data"}, required = true, paramLabel = "JSON", description = "Inline JSON payload.")
        String inline;

        JsonNode read() throws IOException {
            String text = inline != null ? inline
                    : "-".equals(file.toString()) ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                    : Files.readString(file);
            try {
                return MAPPER.readTree(text);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Payload is not valid JSON: " + e.getOriginalMessage());
            }
        }
    }

    static int reportInvalid(BaseCommand cmd, List<String> errors, String context) {
        if (cmd.jsonOutput()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("valid", false);
            m.put("against", context);
            m.put("errors", errors);
            cmd.printJson(m);
        } else {
            cmd.out().println("INVALID against " + context);
            errors.forEach(e -> cmd.out().println("  - " + e));
        }
        return INVALID;
    }

    static int reportMismatch(BaseCommand cmd, SchemaTypeMismatchException e) {
        if (cmd.jsonOutput()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("matches", false);
            m.put("errors", e.report().errors());
            m.put("warnings", e.report().warnings());
            cmd.printJson(m);
        } else {
            cmd.out().println("TYPE MISMATCH: " + e.getMessage());
        }
        return TypeCommands.TYPE_MISMATCH;
    }

    @Command(name = "validate",
            description = "Map a JSON payload into a Java class and check its contract (required properties, types, "
                    + "Bean Validation constraints). Exits with 4 when invalid.",
            mixinStandardHelpOptions = true)
    static final class ValidateCmd extends BaseCommand {
        @Mixin
        JavaClassInput model;

        @ArgGroup(exclusive = true, multiplicity = "1")
        JsonInput input;

        @Override
        int run() throws Exception {
            Class<?> type = model.load();
            String context = type.getName();
            try {
                new JavaContract(MAPPER).read(input.read(), type);
            } catch (SerdeException e) {
                if (e.isValidationFailure()) {
                    return reportInvalid(this, e.validationErrors(), context);
                }
                throw new IllegalArgumentException(e.getMessage());
            }
            print(Map.of("valid", true, "against", context), "VALID against " + context);
            return 0;
        }
    }

    @Command(name = "encode",
            description = "Map a JSON payload into a Java class, enforce its contract and frame it with the schema id "
                    + "generated from that class (registry wire format).",
            mixinStandardHelpOptions = true)
    static final class EncodeCmd extends BaseCommand {
        @Parameters(index = "0", description = "Subject name.")
        String subject;

        @Mixin
        JavaClassInput model;

        @ArgGroup(exclusive = true, multiplicity = "1")
        JsonInput input;

        @Option(names = "--register", description = "Register the class's schema under the subject if it isn't yet.")
        boolean register;

        @Option(names = "--out", paramLabel = "PATH", description = "Write binary message to a file (default: print base64).")
        Path outFile;

        @Override
        int run() throws Exception {
            Class<?> type = model.load();
            JsonSchemaSerializer serializer = JsonSchemaSerializer.builder(client())
                    .subject(subject).forType(type).autoRegister(register).build();
            byte[] message;
            try {
                Object value = new JavaContract(MAPPER).read(input.read(), type);
                message = serializer.serialize(value);
            } catch (SchemaTypeMismatchException e) {
                return reportMismatch(this, e);
            } catch (SerdeException e) {
                if (e.isValidationFailure()) {
                    return reportInvalid(this, e.validationErrors(), type.getName());
                }
                throw new IllegalArgumentException(e.getMessage());
            }
            if (outFile != null) {
                Files.write(outFile, message);
            }
            String b64 = Base64.getEncoder().encodeToString(message);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schemaId", serializer.schemaId());
            m.put("subject", subject);
            m.put("version", serializer.schemaVersion());
            m.put("bytes", message.length);
            m.put(outFile != null ? "file" : "base64", outFile != null ? outFile.toString() : b64);
            print(m, outFile != null
                    ? "Wrote " + message.length + " bytes (schema id " + serializer.schemaId() + ") to " + outFile
                    : b64);
            return 0;
        }
    }

    @Command(name = "decode",
            description = "Decode a framed message. With --class, map it into that Java class and enforce its contract.",
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

        @Option(names = {"-c", "--class"}, paramLabel = "FQCN",
                description = "Java model to decode into (enforces its contract and checks it against the schema).")
        String className;

        @Option(names = {"--classpath", "--cp"}, paramLabel = "PATH", split = "${sys:path.separator}",
                description = "Directories or jars containing the class.")
        List<Path> classpath = new ArrayList<>();

        @Option(names = "--subject", description = "Require the schema to be registered under this subject.")
        String subject;

        @Override
        int run() throws Exception {
            byte[] message;
            try {
                message = source.file != null ? Files.readAllBytes(source.file)
                        : Base64.getDecoder().decode(source.base64.strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("--base64 value is not valid base64");
            }
            Class<?> type = JsonNode.class;
            if (className != null) {
                JavaClassInput loader = new JavaClassInput();
                loader.className = className;
                loader.classpath = classpath;
                type = loader.load();
            }
            JsonSchemaDeserializer.Builder b = JsonSchemaDeserializer.builder(client());
            if (subject != null) {
                b.expectedSubject(subject);
            }
            if (className != null) {
                b.targetType(type);
            }
            DeserializedMessage<?> msg;
            try {
                msg = b.build().deserializeWithMetadata(message, type);
            } catch (SchemaTypeMismatchException e) {
                return reportMismatch(this, e);
            } catch (SerdeException e) {
                if (e.isValidationFailure()) {
                    return reportInvalid(this, e.validationErrors(), "schema id " + WireFormat.decode(message).schemaId()
                            + " as " + type.getName());
                }
                throw new IllegalArgumentException(e.getMessage());
            }
            JsonNode payload = MAPPER.valueToTree(msg.value());
            if (jsonOutput()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("schemaId", msg.schemaId());
                if (msg.subject() != null) {
                    m.put("subject", msg.subject());
                    m.put("version", msg.version());
                }
                if (className != null) {
                    m.put("class", type.getName());
                }
                m.put("payload", payload);
                printJson(m);
            } else {
                out().println("Schema ID: " + msg.schemaId()
                        + (msg.subject() != null ? " (" + msg.subject() + " v" + msg.version() + ")" : "")
                        + (className != null ? " as " + type.getName() : " (raw JSON; pass --class to enforce a model)"));
                out().println(JSON.writeValueAsString(payload));
            }
            return 0;
        }
    }
}
