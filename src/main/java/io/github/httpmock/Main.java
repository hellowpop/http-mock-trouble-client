package io.github.httpmock;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class Main {
    private Main() { }
    public static void main(String[] args) {
        int code = run(args, new PrintStream(System.out, true, StandardCharsets.UTF_8),
                new PrintStream(System.err, true, StandardCharsets.UTF_8));
        if (code != 0) System.exit(code);
    }
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h")) {
            out.println(help());
            return 0;
        }
        try {
            ClientConfig config = ConfigLoader.load(args);
            if ("application".equals(config.mode())) {
                if (GraphicsEnvironment.isHeadless()) throw new IllegalArgumentException("Swing application 실행에는 그래픽 화면이 필요합니다.");
                SwingUtilities.invokeAndWait(() -> ClientWindow.open(config));
                return 0;
            }
            out.println("Target: " + config.targetServerUrl() + ", types=" + config.errorTypes().size()
                    + ", delay=" + config.delay() + " ms");
            var results = new SuiteRunner().run(config, config.errorTypes(), event -> out.println(event), (i, r) -> {});
            long failures = results.stream().filter(CaseResult::failed).count();
            out.println("완료: " + results.size() + "/" + config.errorTypes().size() + ", 실행 오류=" + failures);
            return failures > 0 || results.size() != config.errorTypes().size() ? 1 : 0;
        } catch (Exception e) {
            err.println("설정/시작 오류: " + e.getMessage());
            err.println("--help로 실행 옵션을 확인하세요.");
            return 2;
        }
    }
    private static String help() {
        return """
                HTTP Mock Trouble Client (Java 21)
                java -jar http-mock-trouble-client.jar [options]
                  --config <file.yml>            YAML 설정 파일 (UTF-8)
                  --mode <cli|application>       기본 cli
                  --target-server-url <url>      기본 http://localhost:8080
                  --error-types <type,type,...>  지정 순서로 실행; 기본 전체
                  --delay <ms>                  케이스 사이 delay; 기본 1000
                  --max-timeout <ms>            케이스 전체 제한; 기본 5000
                  --connect-timeout <ms>        TCP 연결 제한; 기본 3000
                  --socket-close-delay <ms>     연결 후 종료까지 대기; 기본 1000
                  --help, -h                    도움말
                Types: socket-close, invalid-initial-line, initial-line-wait,
                       invalid-header, content-length-short, content-length-long
                CLI 옵션은 YAML을 덮어씁니다. --key=value 형식도 지원합니다.
                종료 코드: 0 관찰 완료, 1 timeout/통신 오류/중지, 2 설정/시작 오류
                """;
    }
}
