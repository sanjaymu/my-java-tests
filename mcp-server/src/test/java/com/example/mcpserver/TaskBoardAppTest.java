package com.example.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.junit.jupiter.api.Test;

class TaskBoardAppTest {

    private final TaskStore store = new TaskStore();
    private final TaskBoardApp app = new TaskBoardApp(store);

    @Test
    void toolReturnsTextForModelAndStructuredTasksForUi() {
        store.add("write docs");

        CallToolResult result = app.showTaskBoard();

        assertThat(((TextContent) result.content().getFirst()).text()).contains("#1 write docs");
        assertThat(result.structuredContent()).isEqualTo(Map.of("tasks", List.of(new Task(1, "write docs", false))));
    }

    @Test
    void toolMetaLinksToUiResource() {
        assertThat(new TaskBoardApp.TaskBoardToolMeta().getMeta())
                .isEqualTo(Map.of("ui", Map.of("resourceUri", "ui://tasks/board")));
    }

    @Test
    void resourceServesMcpAppHtml() {
        var contents = (TextResourceContents) app.taskBoard().contents().getFirst();

        assertThat(contents.uri()).isEqualTo("ui://tasks/board");
        assertThat(contents.mimeType()).isEqualTo("text/html;profile=mcp-app");
        assertThat(contents.text())
                .startsWith("<!DOCTYPE html>")
                .contains("ui/initialize", "ui/notifications/initialized", "ui/notifications/tool-result")
                .contains("show_task_board", "add_task", "complete_task");
    }
}
