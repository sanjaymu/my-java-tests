package com.example.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Starts the server on a random port and talks to it with a real MCP client over Streamable HTTP,
 * the same way Claude Desktop, MCP Inspector or a Spring AI app would.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class McpServerIT {

    @LocalServerPort
    private int port;

    private McpSyncClient client;

    @BeforeEach
    void connect() {
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port).build()).build();
        client.initialize();
    }

    @AfterEach
    void disconnect() {
        client.closeGracefully();
    }

    @Test
    void serverIdentifiesItself() {
        assertThat(client.getServerInfo().name()).isEqualTo("sample-task-server");
    }

    @Test
    void listsAllToolsWithDescriptionsAndSchemas() {
        var tools = client.listTools().tools();

        assertThat(tools).extracting(Tool::name)
                .containsExactlyInAnyOrder("add_task", "list_tasks", "complete_task", "get_current_time",
                        "show_task_board");
        Tool addTask = tools.stream().filter(t -> t.name().equals("add_task")).findFirst().orElseThrow();
        assertThat(addTask.description()).isNotBlank();
        // inputSchema is the tool's JSON Schema: {"type":"object","properties":{...},"required":[...]}
        Map<String, Object> schema = addTask.inputSchema();
        assertThat(schema).extractingByKey("properties").asInstanceOf(InstanceOfAssertFactories.MAP).containsKey("title");
        assertThat(schema).extractingByKey("required").asInstanceOf(InstanceOfAssertFactories.LIST).contains("title");
    }

    @Test
    void addThenListTasks() {
        CallToolResult added = client.callTool(new CallToolRequest("add_task", Map.of("title", "integration test task")));
        assertThat(added.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(added)).contains("integration test task");

        CallToolResult listed = client.callTool(new CallToolRequest("list_tasks", Map.of()));
        assertThat(text(listed)).contains("integration test task");
    }

    @Test
    void toolErrorsAreReturnedAsErrorResults() {
        CallToolResult result = client.callTool(new CallToolRequest("complete_task", Map.of("id", 9999)));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("No task with id 9999");
    }

    @Test
    void optionalToolParameterCanBeOmitted() {
        CallToolResult result = client.callTool(new CallToolRequest("get_current_time", Map.of()));

        assertThat(text(result)).contains("(UTC)");
    }

    @Test
    void readsTaskSummaryResource() {
        client.callTool(new CallToolRequest("add_task", Map.of("title", "resource check")));

        var contents = (TextResourceContents) client.readResource(new ReadResourceRequest("tasks://summary"))
                .contents().getFirst();

        assertThat(contents.text()).contains("resource check");
    }

    @Test
    void getsPlanMyDayPrompt() {
        var result = client.getPrompt(new GetPromptRequest("plan_my_day", Map.of("focus", "deep work")));

        assertThat(((TextContent) result.messages().getFirst().content()).text()).contains("Prioritise deep work.");
    }

    @Test
    void taskBoardToolLinksToUiResource() {
        Tool board = client.listTools().tools().stream()
                .filter(t -> t.name().equals("show_task_board")).findFirst().orElseThrow();

        assertThat(board.meta()).isEqualTo(Map.of("ui", Map.of("resourceUri", "ui://tasks/board")));
    }

    @Test
    void taskBoardToolReturnsTextAndStructuredContent() {
        client.callTool(new CallToolRequest("add_task", Map.of("title", "board check")));

        CallToolResult result = client.callTool(new CallToolRequest("show_task_board", Map.of()));

        assertThat(text(result)).contains("board check");
        assertThat(result.structuredContent()).asInstanceOf(InstanceOfAssertFactories.MAP)
                .extractingByKey("tasks").asInstanceOf(InstanceOfAssertFactories.LIST)
                .anySatisfy(task -> assertThat(task).asInstanceOf(InstanceOfAssertFactories.MAP)
                        .containsEntry("title", "board check")
                        .containsEntry("done", false));
    }

    @Test
    void readsTaskBoardUiResource() {
        var contents = (TextResourceContents) client.readResource(new ReadResourceRequest("ui://tasks/board"))
                .contents().getFirst();

        assertThat(contents.mimeType()).isEqualTo("text/html;profile=mcp-app");
        assertThat(contents.text()).startsWith("<!DOCTYPE html>").contains("ui/initialize");
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }
}
