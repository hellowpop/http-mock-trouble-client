package io.github.httpmock;

public record CaseResult(ErrorType type, Outcome outcome, long elapsedMillis, String summary) {
    public enum Outcome { CLIENT_CLOSED, SERVER_CLOSED, ERROR_RESPONSE, RESPONSE_RECEIVED,
        TIMEOUT, NETWORK_ERROR, RESPONSE_TOO_LARGE, CANCELLED }
    public boolean failed() {
        return switch (outcome) {
            case TIMEOUT, NETWORK_ERROR, RESPONSE_TOO_LARGE, CANCELLED -> true;
            default -> false;
        };
    }
    @Override public String toString() {
        return type.id() + " -> " + outcome + " (" + elapsedMillis + " ms) " + summary;
    }
}
