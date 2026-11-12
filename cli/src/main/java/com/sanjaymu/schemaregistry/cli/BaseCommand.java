package com.sanjaymu.schemaregistry.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sanjaymu.schemaregistry.client.SchemaRegistryClient;
import com.sanjaymu.schemaregistry.client.model.RegisterSchemaRequest;
import com.sanjaymu.schemaregistry.client.model.SchemaReference;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Common plumbing shared by all leaf commands. */
abstract class BaseCommand implements Callable<Integer> {

    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Spec
    CommandSpec spec;

    SrCtl root() {
        return (SrCtl) spec.root().userObject();
    }

    SchemaRegistryClient client() {
        return root().client();
    }

    PrintWriter out() {
        return spec.commandLine().getOut();
    }

    boolean jsonOutput() {
        return root().output == OutputFormat.json;
    }

    void printJson(Object value) {
        try {
            out().println(JSON.writeValueAsString(value));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Prints {@code value} as JSON when {@code -o json}, otherwise prints {@code text}. */
    void print(Object value, String text) {
        if (jsonOutput()) {
            printJson(value);
        } else {
            out().println(text);
        }
    }

    @Override
    public final Integer call() throws Exception {
        int rc = run();
        out().flush();
        return rc;
    }

    abstract int run() throws Exception;

    /** Reads a schema from a file ({@code -} for stdin) or an inline string. */
    static String readSchema(Path file, String inline) throws IOException {
        if (file != null && inline != null) {
            throw new IllegalArgumentException("Specify either --file or --schema, not both");
        }
        if (inline != null) {
            return inline;
        }
        if (file == null) {
            throw new IllegalArgumentException("A schema is required: use --file <path> or --schema <string>");
        }
        if ("-".equals(file.toString())) {
            return new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        return Files.readString(file);
    }

    /** Parses references given as {@code name=subject:version}. */
    static List<SchemaReference> parseReferences(List<String> refs) {
        List<SchemaReference> out = new ArrayList<>();
        if (refs == null) {
            return out;
        }
        for (String r : refs) {
            int eq = r.indexOf('=');
            int colon = r.lastIndexOf(':');
            if (eq <= 0 || colon <= eq + 1 || colon == r.length() - 1) {
                throw new IllegalArgumentException("Invalid reference '" + r + "'; expected name=subject:version");
            }
            int version;
            try {
                version = Integer.parseInt(r.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid reference version in '" + r + "'");
            }
            out.add(new SchemaReference(r.substring(0, eq), r.substring(eq + 1, colon), version));
        }
        return out;
    }

    static RegisterSchemaRequest request(String schema, String type, List<String> refs) {
        return new RegisterSchemaRequest(schema, type, parseReferences(refs));
    }
}
