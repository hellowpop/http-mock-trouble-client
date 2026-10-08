package io.github.httpmock;

import java.net.URI;
import java.util.List;

public record ClientConfig(String mode, URI targetServerUrl, List<ErrorType> errorTypes,
                           int delay, int maxTimeout, int connectTimeout, int socketCloseDelay) {
    public ClientConfig {
        if (!"cli".equals(mode) && !"application".equals(mode))
            throw new IllegalArgumentException("mode는 cli 또는 application이어야 합니다.");
        if (targetServerUrl == null || targetServerUrl.getHost() == null
                || !("http".equalsIgnoreCase(targetServerUrl.getScheme()) || "https".equalsIgnoreCase(targetServerUrl.getScheme()))
                || targetServerUrl.getUserInfo() != null || targetServerUrl.getFragment() != null
                || targetServerUrl.getPort() == 0 || targetServerUrl.getPort() > 65535)
            throw new IllegalArgumentException("target-server-url은 사용자정보/fragment 없는 유효한 http(s) URL이어야 합니다.");
        if (errorTypes == null || errorTypes.isEmpty()) throw new IllegalArgumentException("error-types는 하나 이상 필요합니다.");
        errorTypes = List.copyOf(errorTypes);
        if (delay < 0 || socketCloseDelay < 0 || maxTimeout <= 0 || connectTimeout <= 0)
            throw new IllegalArgumentException("delay/socket-close-delay는 0 이상, timeout은 1 이상이어야 합니다(ms).");
        if (errorTypes.contains(ErrorType.SOCKET_CLOSE) && socketCloseDelay >= maxTimeout)
            throw new IllegalArgumentException("socket-close-delay는 max-timeout보다 작아야 합니다.");
    }
    public int port() { return targetServerUrl.getPort() >= 0 ? targetServerUrl.getPort() : tls() ? 443 : 80; }
    public boolean tls() { return "https".equalsIgnoreCase(targetServerUrl.getScheme()); }
    public String requestTarget() {
        URI ascii = URI.create(targetServerUrl.toASCIIString());
        String path = ascii.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        return path + (ascii.getRawQuery() == null ? "" : "?" + ascii.getRawQuery());
    }
    public String hostHeader() {
        return targetServerUrl.getHost() + (targetServerUrl.getPort() < 0 ? "" : ":" + port());
    }
}
