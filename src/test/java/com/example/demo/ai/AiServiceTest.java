package com.example.demo.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Plain unit test: the ChatClient is mocked, so no call reaches the Claude API.
 */
class AiServiceTest {

    @Test
    void askSendsQuestionToModelAndReturnsAnswer() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().user("What is Spring Boot?").call().content())
                .thenReturn("A Java framework.");

        AiService service = new AiService(builder);

        assertThat(service.ask("What is Spring Boot?")).isEqualTo("A Java framework.");
        verify(builder).defaultSystem(anyString());
    }
}
