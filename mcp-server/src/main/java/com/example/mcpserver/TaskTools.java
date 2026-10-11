package com.example.mcpserver;

import java.util.List;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Stateful tools: each call changes or reads the shared task list.
 */
@Component
public class TaskTools {

    private final TaskStore store;

    public TaskTools(TaskStore store) {
        this.store = store;
    }

    @McpTool(name = "add_task", description = "Add a task to the to-do list and return it with its new id")
    public Task addTask(@McpToolParam(description = "Short description of the task") String title) {
        return store.add(title);
    }

    @McpTool(name = "list_tasks", description = "List all tasks with their id, title and whether they are done")
    public List<Task> listTasks() {
        return store.list();
    }

    @McpTool(name = "complete_task", description = "Mark a task as done by its id")
    public Task completeTask(@McpToolParam(description = "Id of the task to complete") int id) {
        return store.complete(id);
    }
}
