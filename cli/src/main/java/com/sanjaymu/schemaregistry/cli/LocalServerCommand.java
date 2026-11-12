package com.sanjaymu.schemaregistry.cli;

import com.sanjaymu.schemaregistry.local.InMemorySchemaRegistry;
import com.sanjaymu.schemaregistry.local.LocalSchemaRegistryServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@Command(name = "local-server",
        description = "Run an in-memory schema registry for local development (data is not persisted).",
        mixinStandardHelpOptions = true)
final class LocalServerCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = {"-p", "--port"}, defaultValue = "8081", description = "Port to listen on (default: ${DEFAULT-VALUE}).")
    int port;

    @Option(names = "--host", defaultValue = "127.0.0.1", description = "Interface to bind (default: ${DEFAULT-VALUE}).")
    String host;

    @Override
    public Integer call() throws Exception {
        LocalSchemaRegistryServer server = LocalSchemaRegistryServer.start(host, port, new InMemorySchemaRegistry());
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stopped.countDown();
        }));
        spec.commandLine().getOut().println("Local schema registry listening on " + server.url()
                + " (Ctrl+C to stop)");
        spec.commandLine().getOut().flush();
        stopped.await();
        return 0;
    }
}
