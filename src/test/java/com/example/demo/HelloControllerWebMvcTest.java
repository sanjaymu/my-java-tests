package com.example.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.demo.security.SecurityConfig;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice test: loads only the MVC infrastructure and HelloController.
 */
@WebMvcTest(HelloController.class)
@Import(SecurityConfig.class)
class HelloControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void helloWithoutNameUsesDefault() throws Exception {
        mockMvc.perform(get("/hello"))
                .andExpect(status().isOk())
                .andExpect(content().string("Hello, World!"));
    }

    @Test
    void helloWithName() throws Exception {
        mockMvc.perform(get("/hello").param("name", "Spring"))
                .andExpect(status().isOk())
                .andExpect(content().string("Hello, Spring!"));
    }
}
