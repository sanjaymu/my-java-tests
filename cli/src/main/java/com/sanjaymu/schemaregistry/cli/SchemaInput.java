package com.sanjaymu.schemaregistry.cli;

import com.sanjaymu.schemaregistry.client.model.RegisterSchemaRequest;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Mixin for commands that take a schema as input. */
final class SchemaInput {

    @Option(names = {"-f", "--file"}, paramLabel = "PATH", description = "Schema file ('-' for stdin).")
    Path file;

    @Option(names = {"-s", "--schema"}, paramLabel = "SCHEMA", description = "Inline schema string.")
    String schema;

    @Option(names = {"-t", "--type"}, defaultValue = "AVRO",
            description = "Schema type: AVRO, JSON or PROTOBUF (default: ${DEFAULT-VALUE}).")
    String type;

    @Option(names = {"-r", "--ref"}, paramLabel = "NAME=SUBJECT:VERSION",
            description = "Schema reference (repeatable).")
    List<String> references;

    RegisterSchemaRequest toRequest() throws IOException {
        return BaseCommand.request(BaseCommand.readSchema(file, schema), type.toUpperCase(), references);
    }
}
