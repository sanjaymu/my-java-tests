package com.example.mcpserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.MetaProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * MCP App (extension io.modelcontextprotocol/ui): a tool linked to a ui:// HTML resource.
 * Hosts that support MCP Apps render the HTML in a sandboxed iframe and pass it the tool result;
 * other hosts just use the tool's text content.
 */
@Component
public class TaskBoardApp {

    static final String BOARD_URI = "ui://tasks/board";
    static final String MCP_APP_MIME_TYPE = "text/html;profile=mcp-app";

    private final TaskStore store;
    private final String boardHtml;

    public TaskBoardApp(TaskStore store) {
        this.store = store;
        this.boardHtml = loadHtml("mcp-app/task-board.html");
    }

    @McpTool(name = "show_task_board",
            description = "Show the task list as an interactive board where the user can add and complete tasks",
            metaProvider = TaskBoardToolMeta.class)
    public CallToolResult showTaskBoard() {
        // content: text for the model and for hosts without MCP Apps support.
        // structuredContent: data for the board UI to render.
        return CallToolResult.builder()
                .content(List.of(new TextContent(TaskResources.summary(store))))
                .structuredContent(Map.of("tasks", store.list()))
                .build();
    }

    @McpResource(uri = BOARD_URI, name = "task-board", mimeType = MCP_APP_MIME_TYPE,
            description = "Interactive task board UI for the show_task_board tool")
    public ReadResourceResult taskBoard() {
        return new ReadResourceResult(List.of(new TextResourceContents(BOARD_URI, MCP_APP_MIME_TYPE, boardHtml)));
    }

    private static String loadHtml(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load " + path, e);
        }
    }

    /**
     * Adds {@code _meta.ui.resourceUri} to the tool definition, which tells the host which UI renders its results.
     * Visibility is left at the default (model and app), so both the model and the board can call it.
     */
    public static class TaskBoardToolMeta implements MetaProvider {

        @Override
        public Map<String, Object> getMeta() {
            return Map.of("ui", Map.of("resourceUri", BOARD_URI));
        }
    }
}
