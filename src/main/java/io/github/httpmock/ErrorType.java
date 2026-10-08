package io.github.httpmock;

import java.util.Locale;

public enum ErrorType {
    SOCKET_CLOSE("socket-close", "연결 후 지정 시간 뒤 소켓 종료"),
    INVALID_INITIAL_LINE("invalid-initial-line", "잘못된 initial line 전송"),
    INITIAL_LINE_WAIT("initial-line-wait", "정상 initial line 전송 후 서버 종료 대기"),
    INVALID_HEADER("invalid-header", "잘못된 헤더 규격 전송"),
    CONTENT_LENGTH_SHORT("content-length-short", "Content-Length보다 적은 본문 전송"),
    CONTENT_LENGTH_LONG("content-length-long", "Content-Length보다 많은 본문 전송");

    private final String id;
    private final String description;

    ErrorType(String id, String description) { this.id = id; this.description = description; }
    public String id() { return id; }
    public String description() { return description; }
    public static ErrorType parse(String text) {
        String id = text.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (ErrorType type : values()) if (type.id.equals(id)) return type;
        throw new IllegalArgumentException("알 수 없는 error-type: " + text);
    }
}
