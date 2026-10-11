package com.example.mcpserver;

import java.util.List;
import java.util.stream.Collectors;

import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

/**
 * A resource is read-only data the client can load into the model's context, unlike a tool, which the model calls.
 */
@Component
public class TaskResources {

    static final String SUMMARY_URI = "tasks://summary";

    private final TaskStore store;

    public TaskResources(TaskStore store) {
        this.store = store;
    }

    @McpResource(uri = SUMMARY_URI, name = "task-summary", mimeType = "text/plain",
            description = "Plain-text summary of all tasks")
    public ReadResourceResult taskSummary() {
        return new ReadResourceResult(List.of(new TextResourceContents(SUMMARY_URI, "text/plain", summary(store))));
    }

    static String summary(TaskStore store) {
        List<Task> tasks = store.list();
        if (tasks.isEmpty()) {
            return "No tasks.";
        }
        long done = tasks.stream().filter(Task::done).count();
        String lines = tasks.stream()
                .map(t -> "[" + (t.done() ? "x" : " ") + "] #" + t.id() + " " + t.title())
                .collect(Collectors.joining("\n"));
        return done + " of " + tasks.size() + " tasks done\n" + lines;
    }
}
