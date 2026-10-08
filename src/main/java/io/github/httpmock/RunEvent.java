package io.github.httpmock;

import java.time.Instant;

public record RunEvent(Instant time, Level level, ErrorType type, String message) {
    public enum Level { INFO, WARN, ERROR }
    public static RunEvent of(Level level, ErrorType type, String message) {
        return new RunEvent(Instant.now(), level, type, message);
    }
    @Override public String toString() {
        return "[" + time + "] [" + level + "] [" + type.id() + "] " + message;
    }
}
