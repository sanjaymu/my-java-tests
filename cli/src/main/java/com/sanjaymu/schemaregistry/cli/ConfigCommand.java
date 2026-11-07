package com.sanjaymu.schemaregistry.cli;

import com.sanjaymu.schemaregistry.client.model.CompatibilityLevel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.Map;

@Command(name = "config", description = "Get or set compatibility levels.", mixinStandardHelpOptions = true,
        subcommands = {ConfigCommand.GetCmd.class, ConfigCommand.SetCmd.class, ConfigCommand.DeleteCmd.class})
final class ConfigCommand {

    static Map<String, String> result(String subject, CompatibilityLevel level) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("scope", subject == null ? "global" : subject);
        m.put("compatibilityLevel", level.name());
        return m;
    }

    @Command(name = "get", description = "Show the global or subject compatibility level.",
            mixinStandardHelpOptions = true)
    static final class GetCmd extends BaseCommand {
        @Parameters(index = "0", arity = "0..1", description = "Subject (omit for global).")
        String subject;

        @Option(names = "--no-default-to-global",
                description = "Fail instead of falling back to the global level when the subject has none.")
        boolean noDefaultToGlobal;

        @Override
        int run() {
            CompatibilityLevel level = subject == null
                    ? client().getGlobalCompatibility()
                    : client().getSubjectCompatibility(subject, !noDefaultToGlobal);
            print(result(subject, level), level.name());
            return 0;
        }
    }

    @Command(name = "set", description = "Set the global or subject compatibility level.",
            mixinStandardHelpOptions = true)
    static final class SetCmd extends BaseCommand {
        @Parameters(index = "0", description = "Level: ${COMPLETION-CANDIDATES}.")
        CompatibilityLevel level;

        @Option(names = "--subject", description = "Subject (omit for global).")
        String subject;

        @Override
        int run() {
            CompatibilityLevel updated = subject == null
                    ? client().setGlobalCompatibility(level)
                    : client().setSubjectCompatibility(subject, level);
            print(result(subject, updated), "Compatibility for " + (subject == null ? "global" : "'" + subject + "'")
                    + " set to " + updated);
            return 0;
        }
    }

    @Command(name = "delete", description = "Remove a subject-level compatibility override.",
            mixinStandardHelpOptions = true)
    static final class DeleteCmd extends BaseCommand {
        @Parameters(index = "0", description = "Subject.")
        String subject;

        @Override
        int run() {
            CompatibilityLevel removed = client().deleteSubjectCompatibility(subject);
            print(result(subject, removed), "Removed compatibility override " + removed + " from '" + subject + "'");
            return 0;
        }
    }
}
