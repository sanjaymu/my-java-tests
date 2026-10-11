package com.example.mcpserver;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Stateless tool with an optional parameter.
 */
@Component
public class TimeTools {

    private final Clock clock;

    public TimeTools() {
        this(Clock.systemUTC());
    }

    TimeTools(Clock clock) {
        this.clock = clock;
    }

    @McpTool(name = "get_current_time", description = "Get the current date and time in a time zone")
    public String getCurrentTime(
            @McpToolParam(description = "IANA time zone such as Europe/London or Asia/Tokyo. Defaults to UTC.",
                    required = false) String timezone) {
        ZoneId zone;
        try {
            zone = (timezone == null || timezone.isBlank()) ? ZoneId.of("UTC") : ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown time zone: " + timezone);
        }
        return ZonedDateTime.now(clock.withZone(zone)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + " (" + zone + ")";
    }
}
