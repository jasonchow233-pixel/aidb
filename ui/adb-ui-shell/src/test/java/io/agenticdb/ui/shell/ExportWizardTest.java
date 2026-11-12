package io.agenticdb.ui.shell;

import io.agenticdb.transfer.ExportFormat;
import io.agenticdb.transfer.ExportOptions;
import io.agenticdb.transfer.MaskRule;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-86：导出向导「单页」里新增的 Options / Formats 两组控件，导出时是否真的
 * 把值写进 {@link ExportOptions}。
 *
 * <p>⚠️ <b>纯 JUnit，无 JavaFX toolkit 依赖</b>：不构造 {@code ExportWizard} 实例、
 * 不碰任何控件、不加 {@code ApplicationExtension}。只调包私有静态方法
 * {@link ExportWizard#composeOptions}，它正是 {@code start()} 组装选项时走的那一条
 * （控件当前值以纯值实参传进去），所以「控件 → 选项」的映射在这里被静态钉住，
 * 而不需要启动界面。领域层真正改变写出字节的那些断言在
 * {@code adb-transfer} 的 {@code ExportOptionsBehaviorTest}。
 *
 * <h2>变异验证步骤（把生产代码改回旧行为，下面必须红）</h2>
 * <ol>
 *   <li>{@code composeOptions} 改回只 {@code new ExportOptions(6 参)}、丢掉
 *       {@code withAppend/withContinueOnError/withRecordDelimiter/withTextQualifier/withFormats}
 *       → {@link #composeOptionsCarriesEveryNewControlIntoTheOptions()} 红。</li>
 *   <li>任一 {@code withXxx} 在复制时漏带新字段（退回默认） → 同上红，且
 *       {@link #composeOptionsKeepsLegacyOptionsIntact()} 里的 mask / salt 也会掉。</li>
 *   <li>{@code start()} 不再把控件 {@code getValue()/isSelected()} 传给
 *       {@code composeOptions}（比如硬编码默认值） → 本类无法直接抓到，
 *       由 {@code composeOptions} 是 {@code start()} 唯一选项来源这一接线事实 +
 *       {@code TransferWizardTest} 的端到端导出共同守住。</li>
 * </ol>
 */
class ExportWizardTest {

    @Test
    void composeOptionsCarriesEveryNewControlIntoTheOptions() {
        ExportOptions o = ExportWizard.composeOptions(
                ExportFormat.CSV, true, false, StandardCharsets.UTF_8, List.of(), "",
                true, true, "\r\n", '\'', "DMY", "/", false, ".", ',', "BASE64");

        assertThat(o.append()).isTrue();
        assertThat(o.continueOnError()).isTrue();
        assertThat(o.recordDelimiter()).isEqualTo("\r\n");
        assertThat(o.textQualifier()).isEqualTo('\'');
        assertThat(o.dateOrder()).isEqualTo("DMY");
        assertThat(o.dateDelimiter()).isEqualTo("/");
        assertThat(o.zeroPaddingDate()).isFalse();
        assertThat(o.timeDelimiter()).isEqualTo(".");
        assertThat(o.decimalSymbol()).isEqualTo(',');
        assertThat(o.binaryEncoding()).isEqualTo("BASE64");
    }

    @Test
    void composeOptionsKeepsLegacyOptionsIntact() {
        MaskRule rule = new MaskRule("email", MaskRule.MaskMode.HASH);
        ExportOptions o = ExportWizard.composeOptions(
                ExportFormat.TSV, false, true, StandardCharsets.UTF_16LE,
                List.of(rule), "salty",
                false, false, "\n", '"', "YMD", "-", true, ":", '.', "HEX");

        // D-86 之前的六个选项 + P-3 的掩码 / 盐值，一个都不能被新字段挤掉。
        assertThat(o.format()).isEqualTo(ExportFormat.TSV);
        assertThat(o.includeHeader()).isFalse();
        assertThat(o.quoteAll()).isTrue();
        assertThat(o.charset()).isEqualTo(StandardCharsets.UTF_16LE);
        assertThat(o.masks()).containsExactly(rule);
        assertThat(o.maskSalt()).isEqualTo("salty");
        assertThat(o.batchSize()).isEqualTo(500);
        assertThat(o.maxRows()).isZero();
        // 新字段取默认，既不丢、也不被脏值污染。
        assertThat(o.append()).isFalse();
        assertThat(o.continueOnError()).isFalse();
        assertThat(o.recordDelimiter()).isEqualTo("\n");
        assertThat(o.textQualifier()).isEqualTo('"');
        assertThat(o.dateOrder()).isEqualTo("YMD");
        assertThat(o.dateDelimiter()).isEqualTo("-");
        assertThat(o.zeroPaddingDate()).isTrue();
        assertThat(o.timeDelimiter()).isEqualTo(":");
        assertThat(o.decimalSymbol()).isEqualTo('.');
        assertThat(o.binaryEncoding()).isEqualTo("HEX");
    }

    @Test
    void composeOptionsNormalizesIllegalControlValues() {
        // 控件理论上不会给出这些值，但归一化是 ExportOptions 的硬要求：
        // null / 空串 / 控制字符 / 非法枚举都必须落回安全默认。
        ExportOptions o = ExportWizard.composeOptions(
                ExportFormat.JSON, true, false, null, null, null,
                false, false, "", '\n', "nonsense", null, false, null, '9', "bogus");

        assertThat(o.charset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(o.masks()).isEmpty();
        assertThat(o.maskSalt()).isEmpty();
        assertThat(o.recordDelimiter()).isEqualTo("\n");
        assertThat(o.textQualifier()).isEqualTo('"');
        assertThat(o.dateOrder()).isEqualTo("YMD");
        assertThat(o.dateDelimiter()).isEqualTo("-");
        assertThat(o.timeDelimiter()).isEqualTo(":");
        assertThat(o.decimalSymbol()).isEqualTo('.');
        assertThat(o.binaryEncoding()).isEqualTo("HEX");
    }
}
