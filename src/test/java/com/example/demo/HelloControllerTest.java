package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Plain unit test: no Spring context, exercises the controller directly.
 */
class HelloControllerTest {

    private final HelloController controller = new HelloController();

    @Test
    void helloReturnsGreetingForName() {
        assertThat(controller.hello("Spring")).isEqualTo("Hello, Spring!");
    }

    @Test
    void helloReturnsDefaultGreeting() {
        assertThat(controller.hello("World")).isEqualTo("Hello, World!");
    }
}
