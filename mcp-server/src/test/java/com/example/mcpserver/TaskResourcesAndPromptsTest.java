package com.example.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.junit.jupiter.api.Test;

class TaskResourcesAndPromptsTest {

    private final TaskStore store = new TaskStore();

    @Test
    void summaryOfEmptyList() {
        assertThat(TaskResources.summary(store)).isEqualTo("No tasks.");
    }

    @Test
    void resourceSummarisesTasks() {
        store.add("one");
        store.complete(store.add("two").id());

        var contents = (TextResourceContents) new TaskResources(store).taskSummary().contents().getFirst();

        assertThat(contents.uri()).isEqualTo("tasks://summary");
        assertThat(contents.text()).isEqualTo("1 of 2 tasks done\n[ ] #1 one\n[x] #2 two");
    }

    @Test
    void promptIncludesTasksAndFocus() {
        store.add("pay bills");

        var message = new TaskPrompts(store).planMyDay("urgent work first").messages().getFirst();
        String text = ((TextContent) message.content()).text();

        assertThat(text).contains("#1 pay bills").contains("Prioritise urgent work first.");
    }
}
