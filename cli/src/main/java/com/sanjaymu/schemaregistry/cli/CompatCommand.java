package com.sanjaymu.schemaregistry.cli;

import com.sanjaymu.schemaregistry.client.model.CompatibilityResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "compat", description = "Test schema compatibility.", mixinStandardHelpOptions = true,
        subcommands = CompatCommand.CheckCmd.class)
final class CompatCommand {

    @Command(name = "check", description = "Test a schema against a subject. Exits with 3 when incompatible.",
            mixinStandardHelpOptions = true)
    static final class CheckCmd extends BaseCommand {
        @Parameters(index = "0", description = "Subject name.")
        String subject;

        @Mixin
        SchemaInput input;

        @Option(names = {"-v", "--version"},
                description = "Test against a single version (number or 'latest'). "
                        + "By default tests against all versions per the subject's compatibility level.")
        String version;

        @Option(names = "--verbose", description = "Include incompatibility details.")
        boolean verbose;

        @Override
        int run() throws Exception {
            CompatibilityResult r = version == null
                    ? client().testCompatibility(subject, input.toRequest(), verbose)
                    : client().testCompatibility(subject, version, input.toRequest(), verbose);
            if (jsonOutput()) {
                printJson(r);
            } else {
                out().println(r.compatible() ? "COMPATIBLE" : "INCOMPATIBLE");
                r.messages().forEach(m -> out().println("  - " + m));
            }
            return r.compatible() ? 0 : 3;
        }
    }
}
