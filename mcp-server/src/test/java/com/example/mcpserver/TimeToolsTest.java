package com.example.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class TimeToolsTest {

    private final TimeTools tools = new TimeTools(Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void defaultsToUtc() {
        assertThat(tools.getCurrentTime(null)).isEqualTo("2026-01-15T12:00:00Z (UTC)");
    }

    @Test
    void convertsToRequestedZone() {
        assertThat(tools.getCurrentTime("Asia/Tokyo")).isEqualTo("2026-01-15T21:00:00+09:00 (Asia/Tokyo)");
    }

    @Test
    void rejectsUnknownZone() {
        assertThatThrownBy(() -> tools.getCurrentTime("Mars/Olympus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mars/Olympus");
    }
}
