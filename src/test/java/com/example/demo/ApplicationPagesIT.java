package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Integration test for the welcome page, the custom 404 page and the health check endpoints.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class ApplicationPagesIT {

    @Autowired
    private RestTestClient client;

    @Test
    void rootServesWelcomePage() {
        client.get().uri("/")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("Welcome to the Demo App"));
    }

    @Test
    void unknownPathServesCustom404PageToBrowsers() {
        client.get().uri("/does-not-exist")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("Page Not Found"));
    }

    @Test
    void unknownPathReturnsJson404ToApiClients() {
        client.get().uri("/does-not-exist")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.path").isEqualTo("/does-not-exist");
    }

    @Test
    void healthEndpointReportsUp() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void livenessAndReadinessProbesReportUp() {
        for (String probe : new String[] {"liveness", "readiness"}) {
            client.get().uri("/actuator/health/" + probe)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo("UP");
        }
    }
}
