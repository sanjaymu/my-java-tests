package com.sanjaymu.schemaregistry.cli;

import com.sanjaymu.schemaregistry.client.model.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.Map;

@Command(name = "mode", description = "Get or set registry modes.", mixinStandardHelpOptions = true,
        subcommands = {ModeCommand.GetCmd.class, ModeCommand.SetCmd.class, ModeCommand.DeleteCmd.class})
final class ModeCommand {

    static Map<String, String> result(String subject, Mode mode) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("scope", subject == null ? "global" : subject);
        m.put("mode", mode.name());
        return m;
    }

    @Command(name = "get", description = "Show the global or subject mode.", mixinStandardHelpOptions = true)
    static final class GetCmd extends BaseCommand {
        @Parameters(index = "0", arity = "0..1", description = "Subject (omit for global).")
        String subject;

        @Option(names = "--no-default-to-global",
                description = "Fail instead of falling back to the global mode when the subject has none.")
        boolean noDefaultToGlobal;

        @Override
        int run() {
            Mode mode = subject == null ? client().getGlobalMode() : client().getSubjectMode(subject, !noDefaultToGlobal);
            print(result(subject, mode), mode.name());
            return 0;
        }
    }

    @Command(name = "set", description = "Set the global or subject mode.", mixinStandardHelpOptions = true)
    static final class SetCmd extends BaseCommand {
        @Parameters(index = "0", description = "Mode: ${COMPLETION-CANDIDATES}.")
        Mode mode;

        @Option(names = "--subject", description = "Subject (omit for global).")
        String subject;

        @Override
        int run() {
            Mode updated = subject == null ? client().setGlobalMode(mode) : client().setSubjectMode(subject, mode);
            print(result(subject, updated), "Mode for " + (subject == null ? "global" : "'" + subject + "'")
                    + " set to " + updated);
            return 0;
        }
    }

    @Command(name = "delete", description = "Remove a subject-level mode override.", mixinStandardHelpOptions = true)
    static final class DeleteCmd extends BaseCommand {
        @Parameters(index = "0", description = "Subject.")
        String subject;

        @Override
        int run() {
            Mode removed = client().deleteSubjectMode(subject);
            print(result(subject, removed), "Removed mode override " + removed + " from '" + subject + "'");
            return 0;
        }
    }
}
