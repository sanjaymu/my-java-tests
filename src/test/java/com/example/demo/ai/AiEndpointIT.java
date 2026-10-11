package com.example.demo.ai;

import static org.mockito.Mockito.when;

import com.example.demo.security.ApiKeyAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Integration test: full application over HTTP. AiService is mocked so no call reaches the Claude API.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "app.security.api-key=test-key")
@AutoConfigureRestTestClient
class AiEndpointIT {

    @Autowired
    private RestTestClient client;

    @MockitoBean
    private AiService aiService;

    @Test
    void askWithoutApiKeyIsUnauthorized() {
        client.get().uri("/ai/ask?question=hi")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void askWithValidApiKeyReturnsAnswer() {
        when(aiService.ask("hi")).thenReturn("Hello!");

        client.get().uri("/ai/ask?question=hi")
                .header(ApiKeyAuthenticationFilter.HEADER, "test-key")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("Hello!");
    }

    @Test
    void publicEndpointsStillWorkWithoutApiKey() {
        client.get().uri("/hello").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
