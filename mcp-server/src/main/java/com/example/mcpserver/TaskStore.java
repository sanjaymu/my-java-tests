package com.example.mcpserver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * In-memory task list shared by the MCP tools, resource and prompt. Data is lost on restart.
 */
@Component
public class TaskStore {

    private final Map<Integer, Task> tasks = new ConcurrentSkipListMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    public Task add(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Task title must not be blank");
        }
        Task task = new Task(nextId.getAndIncrement(), title.strip(), false);
        tasks.put(task.id(), task);
        return task;
    }

    public List<Task> list() {
        return List.copyOf(tasks.values());
    }

    public Task complete(int id) {
        Task updated = tasks.computeIfPresent(id, (key, task) -> new Task(task.id(), task.title(), true));
        if (updated == null) {
            throw new IllegalArgumentException("No task with id " + id);
        }
        return updated;
    }
}
