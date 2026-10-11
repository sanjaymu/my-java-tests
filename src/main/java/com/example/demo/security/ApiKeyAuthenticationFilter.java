package com.example.demo.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request when its {@code X-API-Key} header matches the configured key.
 * Requests without a valid key stay anonymous; the security rules decide whether that is allowed.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final byte[] expectedKey;

    public ApiKeyAuthenticationFilter(String expectedKey) {
        this.expectedKey = expectedKey == null ? new byte[0] : expectedKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String providedKey = request.getHeader(HEADER);
        if (providedKey != null && isValid(providedKey)) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "api-client", null, List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }

    private boolean isValid(String providedKey) {
        // An unconfigured key never matches, so /ai/** fails closed.
        if (expectedKey.length == 0) {
            return false;
        }
        // Constant-time comparison so response timing doesn't leak the key.
        return MessageDigest.isEqual(expectedKey, providedKey.getBytes(StandardCharsets.UTF_8));
    }
}
