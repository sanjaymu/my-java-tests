package com.example.demo.ai;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.security.ApiKeyAuthenticationFilter;
import com.example.demo.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice test for /ai/ask with the real security rules and a mocked AiService.
 */
@WebMvcTest(AiController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "app.security.api-key=test-key")
class AiControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiService aiService;

    @Test
    void askWithoutApiKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/ai/ask").param("question", "hi"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void askWithWrongApiKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/ai/ask").param("question", "hi")
                        .header(ApiKeyAuthenticationFilter.HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void askWithValidApiKeyReturnsAnswer() throws Exception {
        when(aiService.ask("What is Spring Boot?")).thenReturn("A Java framework.");

        mockMvc.perform(get("/ai/ask").param("question", "What is Spring Boot?")
                        .header(ApiKeyAuthenticationFilter.HEADER, "test-key"))
                .andExpect(status().isOk())
                .andExpect(content().string("A Java framework."));
    }

    @Test
    void askWithoutQuestionIsBadRequest() throws Exception {
        mockMvc.perform(get("/ai/ask").header(ApiKeyAuthenticationFilter.HEADER, "test-key"))
                .andExpect(status().isBadRequest());
    }
}
