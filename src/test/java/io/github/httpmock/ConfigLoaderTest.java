package io.github.httpmock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {
    @TempDir Path dir;

    @Test void yamlThenCliOverridesPreserveRequestedOrder() throws Exception {
        Path file = dir.resolve("client.yml");
        Files.writeString(file, """
                mode: application
                target-server-url: http://localhost:9999/path?q=1
                error-types: [invalid-header, invalid-initial-line]
                delay: 10
                max-timeout: 700
                socket-close-delay: 20
                """);
        ClientConfig c = ConfigLoader.load(new String[]{"--config", file.toString(), "--mode=cli", "--delay", "30"});
        assertEquals("cli", c.mode());
        assertEquals("/path", c.targetServerUrl().getPath());
        assertEquals(30, c.delay());
        assertEquals(700, c.maxTimeout());
        assertEquals(List.of(ErrorType.INVALID_HEADER, ErrorType.INVALID_INITIAL_LINE), c.errorTypes());
    }

    @Test void cliCanReplaceInvalidYamlValuesBeforeValidation() throws Exception {
        Path file = dir.resolve("client.yml");
        Files.writeString(file, "delay: -10\nerror-types: []\n");
        ClientConfig c = ConfigLoader.load(new String[]{"--config=" + file, "--delay=0", "--error-types=socket-close"});
        assertEquals(0, c.delay());
        assertEquals(List.of(ErrorType.SOCKET_CLOSE), c.errorTypes());
    }

    @Test void acceptsDefaultsAndRepeatedTypes() throws Exception {
        assertEquals(6, ConfigLoader.load(new String[0]).errorTypes().size());
        ClientConfig c = ConfigLoader.load(new String[]{"--error-types", "invalid-header,invalid-header"});
        assertEquals(2, c.errorTypes().size());
    }

    @Test void rejectsInvalidCliInputs() {
        for (String[] args : List.of(
                new String[]{"--delay=-1"}, new String[]{"--delay=1.5"},
                new String[]{"--max-timeout=0"}, new String[]{"--mode=web"},
                new String[]{"--error-types="}, new String[]{"--error-types=no-such-type"},
                new String[]{"--target-server-url=ftp://localhost"}, new String[]{"--target-server-url=http://user:pass@localhost"},
                new String[]{"--target-server-url=http://localhost/#fragment"},
                new String[]{"--target-server-url=http://localhost:99999"},
                new String[]{"--unknown=x"}, new String[]{"--delay"})) {
            assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(args), String.join(" ", args));
        }
    }

    @Test void rejectsWrongYamlShapeUnknownAndDuplicateKeys() throws Exception {
        for (String yaml : List.of("[cli]", "error-types: invalid-header", "error-types: []", "delay: true",
                "delay: 1.5", "typo: 5", "delay: 0\ndelay: 1", "mode: null")) {
            Path file = dir.resolve("bad.yml");
            Files.writeString(file, yaml);
            assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(new String[]{"--config", file.toString()}), yaml);
        }
    }
}
