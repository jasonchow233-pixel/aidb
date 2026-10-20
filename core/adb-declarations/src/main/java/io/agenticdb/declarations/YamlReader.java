package io.agenticdb.declarations;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.Map;

/**
 * YAML 读取封装。
 *
 * <p>用 {@link SafeConstructor} 而不是默认构造器：默认的会实例化 {@code !!java.*}
 * 标签，等于让一份 YAML 能执行任意代码。声明层虽然是仓库内的文件，
 * 但它会被用户编辑、也会被 fork 后分发，不该留着这个口子。
 */
final class YamlReader {

    private YamlReader() {
    }

    static final LoaderOptions OPTIONS = new LoaderOptions();

    static {
        // 重复键直接报错，而不是后者覆盖前者 —— 后者会让 YAML 里两份不同的
        // defaultPort 悄悄取到后面那份，且没有任何提示。
        OPTIONS.setAllowDuplicateKeys(false);
        OPTIONS.setMaxAliasesForCollections(50);
        OPTIONS.setCodePointLimit(3_000_000);
    }

    private static final Yaml INSTANCE = new Yaml(new SafeConstructor(OPTIONS));

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(byte[] content) {
        Object o = INSTANCE.load(new String(content, java.nio.charset.StandardCharsets.UTF_8));
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}