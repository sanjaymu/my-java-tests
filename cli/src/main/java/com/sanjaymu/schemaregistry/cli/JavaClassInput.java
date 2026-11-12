package com.sanjaymu.schemaregistry.cli;

import picocli.CommandLine.Option;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Mixin for commands that load a compiled Java model class. */
final class JavaClassInput {

    @Option(names = {"-c", "--class"}, required = true, paramLabel = "FQCN",
            description = "Fully qualified Java class name, e.g. com.example.Order.")
    String className;

    @Option(names = {"--classpath", "--cp"}, paramLabel = "PATH", split = "${sys:path.separator}",
            description = "Directories or jars containing the class and its dependencies "
                    + "(repeatable or path-separator separated), e.g. target/classes.")
    List<Path> classpath = new ArrayList<>();

    Class<?> load() {
        List<URL> urls = new ArrayList<>();
        for (Path p : classpath) {
            if (!Files.exists(p)) {
                throw new IllegalArgumentException("Classpath entry not found: " + p);
            }
            try {
                urls.add(p.toAbsolutePath().toUri().toURL());
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Invalid classpath entry: " + p);
            }
        }
        ClassLoader loader = urls.isEmpty() ? getClass().getClassLoader()
                : new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalArgumentException("Could not load class " + className
                    + (classpath.isEmpty() ? " (add --classpath with your compiled classes)" : " from " + classpath)
                    + ": " + e);
        }
    }
}
