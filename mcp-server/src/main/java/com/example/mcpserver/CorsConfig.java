package com.example.mcpserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets browser-based MCP hosts (such as the MCP Apps basic-host) call /mcp from another origin.
 * Desktop hosts and server-side clients don't need this.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${mcp.cors.allowed-origins:}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.length == 0) {
            return;
        }
        registry.addMapping("/mcp")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("*")
                // The browser client must be able to read the session id the server assigns.
                .exposedHeaders("Mcp-Session-Id");
    }
}
