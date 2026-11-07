package com.sanjaymu.schemaregistry.cli;

import com.sanjaymu.schemaregistry.client.SchemaRegistryClient;
import com.sanjaymu.schemaregistry.client.SchemaRegistryException;
import com.sanjaymu.schemaregistry.client.model.Schema;
import com.sanjaymu.schemaregistry.client.model.SchemaReference;
import com.sanjaymu.schemaregistry.client.model.SchemaVersion;
import com.sanjaymu.schemaregistry.client.model.SubjectVersion;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Command(name = "schema", description = "Register, fetch, look up and delete schemas.",
        mixinStandardHelpOptions = true,
        subcommands = {
                SchemaCommand.GetCmd.class,
                SchemaCommand.RegisterCmd.class,
                SchemaCommand.LookupCmd.class,
                SchemaCommand.DeleteCmd.class,
                SchemaCommand.TypesCmd.class,
                SchemaCommand.ReferencedByCmd.class
        })
final class SchemaCommand {

    static String pretty(String schema) {
        try {
            return BaseCommand.JSON.writeValueAsString(BaseCommand.JSON.readTree(schema));
        } catch (Exception e) {
            return schema; // not JSON (e.g. protobuf)
        }
    }

    static String describe(SchemaVersion v) {
        StringBuilder sb = new StringBuilder()
                .append("Subject: ").append(v.subject()).append(System.lineSeparator())
                .append("Version: ").append(v.version()).append(System.lineSeparator())
                .append("ID:      ").append(v.id()).append(System.lineSeparator())
                .append("Type:    ").append(v.type()).append(System.lineSeparator());
        appendRefs(sb, v.references());
        sb.append("Schema:").append(System.lineSeparator()).append(pretty(v.schema()));
        return sb.toString();
    }

    private static void appendRefs(StringBuilder sb, List<SchemaReference> refs) {
        if (!refs.isEmpty()) {
            sb.append("References:").append(System.lineSeparator());
            refs.forEach(r -> sb.append("  ").append(r.name()).append(" -> ").append(r.subject()).append(':')
                    .append(r.version()).append(System.lineSeparator()));
        }
    }

    static final class Target {
        @Option(names = "--id", required = true, description = "Global schema id.")
        Integer id;

        @Option(names = "--subject", required = true, description = "Subject name.")
        String subject;
    }

    @Command(name = "get", description = "Fetch a schema by global id or by subject/version.",
            mixinStandardHelpOptions = true)
    static final class GetCmd extends BaseCommand {
        @ArgGroup(exclusive = true, multiplicity = "1")
        Target target;

        @Option(names = {"-v", "--version"}, defaultValue = "latest",
                description = "Version when using --subject (default: ${DEFAULT-VALUE}).")
        String version;

        @Option(names = "--raw", description = "Print only the schema definition.")
        boolean raw;

        @Override
        int run() {
            SchemaRegistryClient c = client();
            if (target.id != null) {
                Schema s = c.getSchemaById(target.id);
                if (raw) {
                    out().println(s.schema());
                } else if (jsonOutput()) {
                    printJson(s);
                } else {
                    StringBuilder sb = new StringBuilder()
                            .append("ID:      ").append(target.id).append(System.lineSeparator())
                            .append("Type:    ").append(s.type()).append(System.lineSeparator());
                    List<SubjectVersion> usages = c.getSubjectVersionsById(target.id);
                    if (!usages.isEmpty()) {
                        sb.append("Used by: ").append(usages.stream().map(u -> u.subject() + ":" + u.version())
                                .collect(Collectors.joining(", "))).append(System.lineSeparator());
                    }
                    appendRefs(sb, s.references());
                    sb.append("Schema:").append(System.lineSeparator()).append(pretty(s.schema()));
                    out().println(sb);
                }
                return 0;
            }
            SchemaVersion v = c.getSchemaVersion(target.subject, version);
            if (raw) {
                out().println(v.schema());
            } else {
                print(v, describe(v));
            }
            return 0;
        }
    }

    @Command(name = "register", description = "Register a schema under a subject.", mixinStandardHelpOptions = true)
    static final class RegisterCmd extends BaseCommand {
        @Parameters(index = "0", description = "Subject name.")
        String subject;

        @Mixin
        SchemaInput input;

        @Override
        int run() throws Exception {
            int id = client().register(subject, input.toRequest());
            print(Map.of("subject", subject, "id", id), "Registered schema under '" + subject + "' with id " + id);
            return 0;
        }
    }

    @Command(name = "lookup", description = "Check whether a schema is registered under a subject.",
            mixinStandardHelpOptions = true)
    static final class LookupCmd extends BaseCommand {
        @Parameters(index = "0", description = "Subject name.")
        String subject;

        @Mixin
        SchemaInput input;

        @Override
        int run() throws Exception {
            try {
                SchemaVersion v = client().lookup(subject, input.toRequest());
                print(v, "Found: subject '" + v.subject() + "' version " + v.version() + " (id " + v.id() + ")");
                return 0;
            } catch (SchemaRegistryException e) {
                if (e.errorCode() == SchemaRegistryException.SCHEMA_NOT_FOUND) {
                    print(Map.of("subject", subject, "found", false), "Schema is not registered under '" + subject + "'");
                    return 2;
                }
                throw e;
            }
        }
    }

    @Command(name = "delete", aliases = "rm", description = "Delete a schema version (soft by default).",
            mixinStandardHelpOptions = true)
    static final class DeleteCmd extends BaseCommand {
        @Parameters(index = "0", description = "Subject name.")
        String subject;

        @Parameters(index = "1", description = "Version number or 'latest'.")
        String version;

        @Option(names = "--permanent", description = "Hard delete (the version must be soft-deleted first).")
        boolean permanent;

        @Override
        int run() {
            int deleted = client().deleteSchemaVersion(subject, version, permanent);
            print(Map.of("subject", subject, "version", deleted, "permanent", permanent),
                    (permanent ? "Permanently deleted" : "Soft-deleted") + " '" + subject + "' version " + deleted);
            return 0;
        }
    }

    @Command(name = "types", description = "List schema types supported by the registry.",
            mixinStandardHelpOptions = true)
    static final class TypesCmd extends BaseCommand {
        @Override
        int run() {
            List<String> types = client().getSchemaTypes();
            print(types, String.join(System.lineSeparator(), types));
            return 0;
        }
    }

    @Command(name = "referenced-by", description = "List schema ids that reference a subject version.",
            mixinStandardHelpOptions = true)
    static final class ReferencedByCmd extends BaseCommand {
        @Parameters(index = "0", description = "Subject name.")
        String subject;

        @Parameters(index = "1", defaultValue = "latest", description = "Version (default: ${DEFAULT-VALUE}).")
        String version;

        @Override
        int run() {
            List<Integer> ids = client().getReferencedBy(subject, version);
            print(ids, ids.isEmpty() ? "(no references)"
                    : ids.stream().map(String::valueOf).collect(Collectors.joining(System.lineSeparator())));
            return 0;
        }
    }
}
