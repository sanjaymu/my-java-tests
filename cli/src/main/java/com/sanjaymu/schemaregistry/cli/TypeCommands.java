package com.sanjaymu.schemaregistry.cli;

import com.sanjaymu.schemaregistry.client.model.RegisterSchemaRequest;
import com.sanjaymu.schemaregistry.client.model.SchemaType;
import com.sanjaymu.schemaregistry.client.model.SchemaVersion;
import com.sanjaymu.schemaregistry.serde.JavaTypeSchemaGenerator;
import com.sanjaymu.schemaregistry.serde.SchemaTypeChecker;
import com.sanjaymu.schemaregistry.serde.SerdeMappers;
import com.sanjaymu.schemaregistry.serde.TypeCheckReport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Commands that tie registered JSON schemas to Java model classes. */
final class TypeCommands {

    /** Exit code when a Java type does not match a schema. */
    static final int TYPE_MISMATCH = 5;

    private TypeCommands() {
    }

    @Command(name = "generate", description = "Generate a JSON schema from a Java class (optionally registering it).",
            mixinStandardHelpOptions = true)
    static final class GenerateCmd extends BaseCommand {
        @Mixin
        JavaClassInput input;

        @Option(names = "--register", paramLabel = "SUBJECT", description = "Register the generated schema under SUBJECT.")
        String subject;

        @Option(names = "--out", paramLabel = "PATH", description = "Also write the schema to a file.")
        Path outFile;

        @Override
        int run() throws Exception {
            Class<?> type = input.load();
            String pretty = JSON.writeValueAsString(JavaTypeSchemaGenerator.generateNode(type));
            if (outFile != null) {
                Files.writeString(outFile, pretty + System.lineSeparator());
            }
            if (subject == null) {
                if (outFile == null || jsonOutput()) {
                    out().println(pretty);
                } else {
                    out().println("Wrote schema for " + type.getName() + " to " + outFile);
                }
                return 0;
            }
            int id = client().register(subject, RegisterSchemaRequest.of(pretty, SchemaType.JSON));
            int version = client().lookup(subject, RegisterSchemaRequest.of(pretty, SchemaType.JSON)).version();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("class", type.getName());
            m.put("subject", subject);
            m.put("id", id);
            m.put("version", version);
            print(m, "Registered schema for " + type.getName() + " under '" + subject + "' as version " + version
                    + " (id " + id + ")");
            return 0;
        }
    }

    @Command(name = "check-type",
            description = "Check that a Java class matches a subject's JSON schema(s). Exits with 5 on mismatch.",
            mixinStandardHelpOptions = true)
    static final class CheckTypeCmd extends BaseCommand {
        @Parameters(index = "0", description = "Subject name.")
        String subject;

        @Mixin
        JavaClassInput input;

        @Option(names = {"-v", "--version"},
                description = "Version to check (number or 'latest'). Default: every registered version.")
        String version;

        @Option(names = "--strict", description = "Treat warnings as failures.")
        boolean strict;

        @Override
        int run() throws Exception {
            Class<?> type = input.load();
            List<SchemaVersion> targets = new ArrayList<>();
            if (version != null) {
                targets.add(client().getSchemaVersion(subject, version));
            } else {
                for (int v : client().getVersions(subject)) {
                    targets.add(client().getSchemaVersion(subject, v));
                }
            }
            SchemaTypeChecker checker = new SchemaTypeChecker(SerdeMappers.defaultMapper());
            boolean failed = false;
            List<Map<String, Object>> results = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (SchemaVersion sv : targets) {
                if (sv.type() != SchemaType.JSON) {
                    throw new IllegalArgumentException("Subject '" + subject + "' version " + sv.version() + " is "
                            + sv.type() + "; type checking supports JSON schemas only");
                }
                TypeCheckReport r = checker.check(sv.schema(), type);
                boolean bad = r.hasErrors() || (strict && !r.warnings().isEmpty());
                failed |= bad;
                String status = r.hasErrors() ? "MISMATCH" : r.warnings().isEmpty() ? "OK" : "OK (with warnings)";
                if (bad && !r.hasErrors()) {
                    status = "MISMATCH (strict)";
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("version", sv.version());
                m.put("id", sv.id());
                m.put("matches", !bad);
                m.put("errors", r.errors());
                m.put("warnings", r.warnings());
                results.add(m);
                text.append(subject).append(" v").append(sv.version()).append(" (id ").append(sv.id()).append("): ")
                        .append(status).append(System.lineSeparator());
                if (!r.isClean()) {
                    text.append(r.format()).append(System.lineSeparator());
                }
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("class", type.getName());
            summary.put("subject", subject);
            summary.put("matches", !failed);
            summary.put("versions", results);
            print(summary, "Checking " + type.getName() + System.lineSeparator() + text.toString().stripTrailing());
            return failed ? TYPE_MISMATCH : 0;
        }
    }
}
