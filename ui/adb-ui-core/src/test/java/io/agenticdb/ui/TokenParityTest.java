package io.agenticdb.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设计令牌门禁：
 * 1. tokens.json 的键与 Tokens.ALL_KEYS 必须一一对应；
 * 2. 两套主题必须包含每个颜色令牌；
 * 3. 业务 CSS 不得出现颜色字面量。
 */
class TokenParityTest {

    @Test
    void jsonKeysMatchGeneratedConstants() throws IOException {
        JsonNode root = new ObjectMapper()
                .readTree(readResource("/tokens/tokens.json"));
        List<String> keys = new ArrayList<>();
        root.path("tokens").fieldNames().forEachRemaining(keys::add);

        assertThat(keys).containsExactlyElementsOf(Tokens.ALL_KEYS);
        assertThat(Tokens.COUNT).isEqualTo(keys.size());
        assertThat(root.path("meta").path("count").asInt()).isEqualTo(keys.size());
    }

    @Test
    void themesContainEveryColorToken() throws IOException {
        JsonNode tokens = new ObjectMapper()
                .readTree(readResource("/tokens/tokens.json")).path("tokens");

        String dark = readResource("/io/agenticdb/ui/theme-dark.css");
        String light = readResource("/io/agenticdb/ui/theme-light.css");

        tokens.fields().forEachRemaining(entry -> {
            if (!"color".equals(entry.getValue().path("type").asText())) {
                return;
            }
            String key = "-adb-" + entry.getKey();
            assertThat(dark).as("dark theme 缺少 %s", key).contains(key + ":");
            assertThat(light).as("light theme 缺少 %s", key).contains(key + ":");
        });
    }

    @Test
    void coreDimensionsMatchSpec() {
        assertThat(Tokens.H_TOOLBAR_DARK).isEqualTo(36);
        assertThat(Tokens.H_STATUSBAR_DARK).isEqualTo(26);
        assertThat(Tokens.H_TABBAR_DARK).isEqualTo(36);
        assertThat(Tokens.SIDEBAR_WIDTH_DARK).isEqualTo(240);
    }

    @Test
    void businessCssHasNoRawColors() throws IOException {
        Path path = Path.of("..", "adb-ui-shell", "src", "main", "resources",
                "io", "agenticdb", "ui", "app.css");
        if (!Files.exists(path)) {
            // IDE 直接以仓库根目录跑测试时的回退路径
            path = Path.of("ui", "adb-ui-shell", "src", "main", "resources",
                    "io", "agenticdb", "ui", "app.css");
        }
        assertThat(path).as("app.css 路径").exists();

        String css = Files.readString(path, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "");

        assertThat(css)
                .as("业务 CSS 不允许写死颜色，必须用 -adb-* 令牌")
                .doesNotContainPattern("#[0-9a-fA-F]{3,8}\\b")
                .doesNotContainPattern("rgba?\\(");
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = TokenParityTest.class.getResourceAsStream(path)) {
            assertThat(stream).as("缺少资源 %s", path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
