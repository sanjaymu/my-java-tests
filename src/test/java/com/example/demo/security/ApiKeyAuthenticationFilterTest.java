package com.example.demo.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Plain unit test for the API key filter, no Spring context.
 */
class ApiKeyAuthenticationFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matchingKeyAuthenticatesRequest() throws Exception {
        Authentication auth = runFilter("secret", "secret");

        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getName()).isEqualTo("api-client");
    }

    @Test
    void wrongKeyLeavesRequestUnauthenticated() throws Exception {
        assertThat(runFilter("secret", "wrong")).isNull();
    }

    @Test
    void missingHeaderLeavesRequestUnauthenticated() throws Exception {
        assertThat(runFilter("secret", null)).isNull();
    }

    @Test
    void unconfiguredKeyRejectsEverything() throws Exception {
        assertThat(runFilter("", "")).isNull();
        assertThat(runFilter(null, "anything")).isNull();
    }

    @Test
    void filterAlwaysContinuesTheChain() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        new ApiKeyAuthenticationFilter("secret")
                .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    private Authentication runFilter(String configuredKey, String headerValue) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/ask");
        if (headerValue != null) {
            request.addHeader(ApiKeyAuthenticationFilter.HEADER, headerValue);
        }
        new ApiKeyAuthenticationFilter(configuredKey)
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
