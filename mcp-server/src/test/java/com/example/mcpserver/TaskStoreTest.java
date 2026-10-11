package com.example.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TaskStoreTest {

    private final TaskStore store = new TaskStore();

    @Test
    void addAssignsIncreasingIds() {
        assertThat(store.add("first").id()).isEqualTo(1);
        assertThat(store.add("second").id()).isEqualTo(2);
    }

    @Test
    void addTrimsTitleAndStartsNotDone() {
        Task task = store.add("  buy milk  ");

        assertThat(task.title()).isEqualTo("buy milk");
        assertThat(task.done()).isFalse();
    }

    @Test
    void addRejectsBlankTitle() {
        assertThatThrownBy(() -> store.add("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listReturnsTasksInIdOrder() {
        store.add("a");
        store.add("b");

        assertThat(store.list()).extracting(Task::title).containsExactly("a", "b");
    }

    @Test
    void completeMarksTaskDone() {
        Task task = store.add("write tests");

        assertThat(store.complete(task.id()).done()).isTrue();
        assertThat(store.list().getFirst().done()).isTrue();
    }

    @Test
    void completeUnknownIdFails() {
        assertThatThrownBy(() -> store.complete(99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }
}
