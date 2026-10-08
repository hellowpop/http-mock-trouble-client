package io.github.httpmock;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class ConfigLoader {
    private static final Set<String> KEYS = Set.of("mode", "target-server-url", "error-types", "delay",
            "max-timeout", "connect-timeout", "socket-close-delay");
    private ConfigLoader() { }

    public static ClientConfig load(String[] args) throws IOException {
        Map<String, String> cli = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) throw new IllegalArgumentException("잘못된 옵션: " + arg);
            int equal = arg.indexOf('=');
            String key = arg.substring(2, equal >= 0 ? equal : arg.length());
            if (!KEYS.contains(key) && !"config".equals(key)) throw new IllegalArgumentException("알 수 없는 옵션: --" + key);
            String value;
            if (equal >= 0) value = arg.substring(equal + 1);
            else {
                if (i + 1 >= args.length || args[i + 1].startsWith("--"))
                    throw new IllegalArgumentException("옵션 값 누락: --" + key);
                value = args[++i];
            }
            if (cli.putIfAbsent(key, value) != null) throw new IllegalArgumentException("중복 옵션: --" + key);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("mode", "cli");
        values.put("target-server-url", "http://localhost:8080");
        values.put("error-types", Arrays.stream(ErrorType.values()).map(ErrorType::id).toList());
        values.put("delay", 1000);
        values.put("max-timeout", 5000);
        values.put("connect-timeout", 3000);
        values.put("socket-close-delay", 1000);
        if (cli.containsKey("config")) {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            try (var reader = Files.newBufferedReader(Path.of(cli.get("config")), StandardCharsets.UTF_8)) {
                Object document = new Yaml(new SafeConstructor(options)).load(reader);
                if (!(document instanceof Map<?, ?> map)) throw new IllegalArgumentException("YAML 최상위는 설정 객체여야 합니다.");
                for (var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key) || !KEYS.contains(key))
                        throw new IllegalArgumentException("알 수 없는 YAML 설정: " + entry.getKey());
                    values.put(key, entry.getValue());
                }
            } catch (YAMLException e) { throw new IllegalArgumentException("잘못된 YAML: " + e.getMessage(), e); }
        }
        cli.forEach((key, value) -> {
            if ("config".equals(key)) return;
            if ("error-types".equals(key)) values.put(key, Arrays.asList(value.split(",", -1)));
            else if (Set.of("delay", "max-timeout", "connect-timeout", "socket-close-delay").contains(key)) {
                try { values.put(key, Integer.parseInt(value)); }
                catch (NumberFormatException e) { throw new IllegalArgumentException(key + "는 정수(ms)여야 합니다."); }
            } else values.put(key, value);
        });
        if (!(values.get("error-types") instanceof List<?> types)) throw new IllegalArgumentException("error-types는 배열이어야 합니다.");
        List<ErrorType> parsed = new ArrayList<>();
        for (Object type : types) parsed.add(ErrorType.parse(string(type, "error-types")));
        return new ClientConfig(string(values.get("mode"), "mode").toLowerCase(Locale.ROOT),
                URI.create(string(values.get("target-server-url"), "target-server-url")), parsed,
                integer(values, "delay"), integer(values, "max-timeout"),
                integer(values, "connect-timeout"), integer(values, "socket-close-delay"));
    }
    private static String string(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(name + "는 빈 값이 아닌 문자열이어야 합니다.");
        return text.trim();
    }
    private static int integer(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() > Integer.MAX_VALUE
                || ((Number) value).longValue() < Integer.MIN_VALUE)
            throw new IllegalArgumentException(key + "는 32비트 정수(ms)여야 합니다.");
        return ((Number) value).intValue();
    }
}
