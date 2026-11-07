package com.sanjaymu.schemaregistry.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Command(name = "subjects", description = "List, inspect and delete subjects.",
        mixinStandardHelpOptions = true,
        subcommands = {
                SubjectsCommand.ListCmd.class,
                SubjectsCommand.VersionsCmd.class,
                SubjectsCommand.DeleteCmd.class
        })
final class SubjectsCommand {

    @Command(name = "list", aliases = "ls", description = "List subjects.", mixinStandardHelpOptions = true)
    static final class ListCmd extends BaseCommand {
        @Option(names = {"-p", "--prefix"}, description = "Only list subjects with this prefix.")
        String prefix;

        @Option(names = "--deleted", description = "Include soft-deleted subjects.")
        boolean deleted;

        @Override
        int run() {
            List<String> subjects = client().getSubjects(prefix, deleted);
            print(subjects, subjects.isEmpty() ? "(no subjects)" : String.join(System.lineSeparator(), subjects));
            return 0;
        }
    }

    @Command(name = "versions", description = "List versions of a subject.", mixinStandardHelpOptions = true)
    static final class VersionsCmd extends BaseCommand {
        @Parameters(index = "0", description = "Subject name.")
        String subject;

        @Option(names = "--deleted", description = "Include soft-deleted versions.")
        boolean deleted;

        @Override
        int run() {
            List<Integer> versions = client().getVersions(subject, deleted);
            print(versions, versions.stream().map(String::valueOf).collect(Collectors.joining(System.lineSeparator())));
            return 0;
        }
    }

    @Command(name = "delete", aliases = "rm", description = "Delete a subject (soft by default).",
            mixinStandardHelpOptions = true)
    static final class DeleteCmd extends BaseCommand {
        @Parameters(index = "0", description = "Subject name.")
        String subject;

        @Option(names = "--permanent", description = "Hard delete (the subject must be soft-deleted first).")
        boolean permanent;

        @Override
        int run() {
            List<Integer> versions = client().deleteSubject(subject, permanent);
            print(Map.of("subject", subject, "deletedVersions", versions, "permanent", permanent),
                    (permanent ? "Permanently deleted" : "Soft-deleted") + " subject '" + subject + "' versions "
                            + versions);
            return 0;
        }
    }
}
