package io.agenticdb.transfer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P-3：列级脱敏的纯函数门禁。
 *
 * <p>脱敏是安全功能：这里钉的是「不泄露原值」这件事本身 —— 长度、首尾、
 * 稳定性、NULL 语义、列名匹配，而不是某个导出格式长什么样（格式归
 * {@link MaskedExportTest}）。
 */
class DataMaskerTest {

    private static final MaskRule EMAIL_FULL =
            new MaskRule("email", MaskRule.MaskMode.FULL);

    @Test
    void fullMaskNeverLeaksTheOriginalLength() {
        assertThat(DataMasker.mask("alice@example.com", EMAIL_FULL)).isEqualTo("******");
        assertThat(DataMasker.mask("a", EMAIL_FULL)).isEqualTo("******");
        // 数字与布尔也走同一条：掩码结果必定是文本，不再保留原 JDBC 类型。
        assertThat(DataMasker.mask(123456789, EMAIL_FULL)).isEqualTo("******");
    }

    @Test
    void edgesKeepsOnlyFirstAndLast() {
        MaskRule rule = new MaskRule("phone", MaskRule.MaskMode.EDGES);
        assertThat(DataMasker.mask("13812345678", rule)).isEqualTo("1******8");
        assertThat(DataMasker.mask("long-value-here", rule)).isEqualTo("l******e");
        assertThat(DataMasker.mask("ab", rule)).isEqualTo("**");
        assertThat(DataMasker.mask("x", rule)).isEqualTo("*");
    }

    @Test
    void hashIsStableComparableAndHidesTheOriginal() {
        MaskRule rule = new MaskRule("ssn", MaskRule.MaskMode.HASH);
        Object first = DataMasker.mask("123-45-6789", rule);
        assertThat(first).isEqualTo(DataMasker.mask("123-45-6789", rule));
        assertThat(first.toString()).startsWith("sha256:");
        assertThat(first.toString()).hasSize("sha256:".length() + 16);
        assertThat(first.toString()).doesNotContain("123-45-6789");
        assertThat(first).isNotEqualTo(DataMasker.mask("123-45-6788", rule));
    }

    @Test
    void nullEmptyAndMissingRulesArePassThrough() {
        assertThat(DataMasker.mask(null, EMAIL_FULL)).isNull();
        assertThat(DataMasker.mask("", EMAIL_FULL)).isEqualTo("");
        assertThat(DataMasker.mask("plain", null)).isEqualTo("plain");
        assertThat(new MaskRule("email", null).mode())
                .as("规则没写模式时默认 FULL，不许悄悄放行")
                .isEqualTo(MaskRule.MaskMode.FULL);
    }

    @Test
    void maskRowOnlyTouchesConfiguredColumnsAndKeepsOrder() {
        // 不能用 List.of：这行故意带一个 NULL，List.of 会直接抛 NPE。
        // 而「NULL 保持 NULL」正是本用例要钉的语义之一。
        List<Object> row = java.util.Arrays.asList(7, "alice@example.com", null, 30);
        List<String> names = List.of("id", "email", "note", "age");
        List<Object> masked = DataMasker.maskRow(row, names,
                List.of(EMAIL_FULL, new MaskRule("age", MaskRule.MaskMode.FULL)));
        assertThat(masked).containsExactly(7, "******", null, "******");
    }

    /** ⭐ 加盐后低熵原值不再等于「任何人都算得出来的那个 SHA-256」。 */
    @Test
    void saltChangesTheHashButStaysStableForTheSameSalt() {
        MaskRule rule = new MaskRule("phone", MaskRule.MaskMode.HASH);
        String plain = "13812345678";
        Object unsalted = DataMasker.mask(plain, rule);
        Object salted = DataMasker.mask(plain, rule, "s3cr3t");

        assertThat(salted).isNotEqualTo(unsalted);
        assertThat(salted).isEqualTo(DataMasker.mask(plain, rule, "s3cr3t"))
                .as("同一个盐值要能跨次导出继续比对");
        assertThat(salted).isNotEqualTo(DataMasker.mask(plain, rule, "another"));
        assertThat(salted.toString()).startsWith("sha256:");
        // 空盐与旧的两参调用完全等价（老脚本 / 老测试不必改）。
        assertThat(DataMasker.mask(plain, rule, "")).isEqualTo(unsalted);
        assertThat(DataMasker.mask(plain, rule, null)).isEqualTo(unsalted);
    }

    /** 盐值必须真正传到整行路径，而不是只有单值 mask() 认它。 */
    @Test
    void maskRowCarriesTheSaltToEveryHashRule() {
        List<Object> row = List.of(1, "13812345678", "alice@example.com");
        List<String> names = List.of("id", "phone", "email");
        List<MaskRule> rules = List.of(
                new MaskRule("phone", MaskRule.MaskMode.HASH),
                new MaskRule("email", MaskRule.MaskMode.HASH));

        List<Object> salted = DataMasker.maskRow(row, names, rules, "k1");
        List<Object> plain = DataMasker.maskRow(row, names, rules);
        assertThat(salted.get(0)).isEqualTo(1);
        assertThat(salted.get(1)).isNotEqualTo(plain.get(1));
        assertThat(salted.get(2)).isNotEqualTo(plain.get(2));
    }

    @Test
    void emptyRulesShortCircuitAndUnknownNamesAreIgnored() {
        List<Object> row = List.of(1, "plain");
        assertThat(DataMasker.maskRow(row, List.of("id", "note"), List.of())).isSameAs(row);
        assertThat(DataMasker.maskRow(row, List.of("id", "note"), null)).isSameAs(row);
        assertThat(DataMasker.maskRow(List.of("secret"), List.of("other"),
                List.of(EMAIL_FULL))).containsExactly("secret");
        assertThat(DataMasker.maskRow(List.of("secret"), List.of(),
                List.of(EMAIL_FULL))).containsExactly("secret");
    }
}
