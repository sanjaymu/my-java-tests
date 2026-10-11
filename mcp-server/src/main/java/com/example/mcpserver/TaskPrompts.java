package com.example.mcpserver;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

/**
 * A prompt is a reusable message template the user picks in the client, filled in with live server data.
 */
@Component
public class TaskPrompts {

    private final TaskStore store;

    public TaskPrompts(TaskStore store) {
        this.store = store;
    }

    @McpPrompt(name = "plan_my_day", description = "Ask the model to plan the day around the current task list")
    public GetPromptResult planMyDay(
            @McpArg(name = "focus", description = "What to prioritise, e.g. 'urgent work first'", required = false)
            String focus) {
        String priority = (focus == null || focus.isBlank()) ? "whatever matters most" : focus;
        String text = "Here is my task list:\n\n" + TaskResources.summary(store)
                + "\n\nPlan my day around the unfinished tasks. Prioritise " + priority + ".";
        return new GetPromptResult("Daily plan based on the task list",
                List.of(new PromptMessage(Role.USER, new TextContent(text))));
    }
}
