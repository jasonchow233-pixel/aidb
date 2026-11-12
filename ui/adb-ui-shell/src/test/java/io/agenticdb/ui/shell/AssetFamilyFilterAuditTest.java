package io.agenticdb.ui.shell;

import io.agenticdb.asset.AssetFamily;
import io.agenticdb.asset.AssetFamilyRegistry;
import io.agenticdb.asset.AssetKind;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P4-1：`DialectBranchAuditTest` 升级版承诺的**动态**半边。
 *
 * <p>静态门禁只保证「UI 不硬编码 {@code AssetKind.XXX} 常量」，保证不了
 * 「资产族注册表里新增一族时，向导的族筛选带会跟着长出一枚 chip」。
 * 这一条按运行时事实核对：每一族的磁贴必须恰好落进<b>一枚</b>非「全部」筛选 chip，
 * 且 chip 数与注册表族数相等。少一族、多一族、两族挤进同一枚 chip，都会红。
 *
 * <p>反过来也钉住「全部」：它必须接受注册表里的每一张磁贴，否则默认视图
 * 就不是「注册表即页面」，R43-b 那条承诺会被筛选器悄悄改掉。
 */
class AssetFamilyFilterAuditTest {

    @Test
    void everyRegisteredFamilyHasExactlyOneFilterChip() {
        AssetFamilyRegistry.reset();
        AssetFamilyRegistry.registerDefaults();
        List<AssetFamily> families = AssetFamilyRegistry.families();
        assertThat(families)
                .as("注册表里一族都没有 —— 这条门禁是假绿")
                .isNotEmpty();

        Set<ConnectionWizard.TileFamily> used = EnumSet.noneOf(ConnectionWizard.TileFamily.class);
        for (AssetFamily family : families) {
            Set<ConnectionWizard.TileFamily> chipsForFamily =
                    EnumSet.noneOf(ConnectionWizard.TileFamily.class);
            Set<AssetKind> kinds = new LinkedHashSet<>(family.kinds());
            assertThat(kinds).as("族 %s 不拥有任何 AssetKind", family).isNotEmpty();

            int tilesSeen = 0;
            for (ConnectionCatalog.Entry entry : ConnectionCatalog.CONNECTIONS) {
                if (!kinds.contains(entry.kind())) {
                    continue;
                }
                tilesSeen++;
                for (ConnectionWizard.TileFamily chip : ConnectionWizard.TileFamily.values()) {
                    if (chip != ConnectionWizard.TileFamily.ALL && chip.accepts(entry)) {
                        chipsForFamily.add(chip);
                    }
                }
            }
            assertThat(tilesSeen)
                    .as("族 %s 在向导里一张磁贴都没有（注册表与页面脱节）", family)
                    .isGreaterThan(0);
            assertThat(chipsForFamily)
                    .as("族 %s 的磁贴必须恰好落进一枚筛选 chip；挤进两枚 = 筛选逻辑写错，"
                            + "一枚都没有 = 加了一族却没给它 chip", family)
                    .hasSize(1);
            used.addAll(chipsForFamily);
        }

        assertThat(used)
                .as("筛选 chip（除「全部」）与注册表里的族一一对应")
                .hasSize(families.size());
        assertThat(ConnectionWizard.TileFamily.values())
                .as("ALL + 每个族一枚，不多不少")
                .hasSize(families.size() + 1);
    }

    @Test
    void theAllChipAcceptsEveryRegisteredTile() {
        AssetFamilyRegistry.reset();
        AssetFamilyRegistry.registerDefaults();
        assertThat(ConnectionCatalog.CONNECTIONS)
                .as("磁贴清单不能是空的")
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(ConnectionWizard.TileFamily.ALL.accepts(entry))
                        .as("「全部」必须接受 %s", entry.id())
                        .isTrue());
        assertThat(ConnectionCatalog.CONNECTIONS)
                .as("注册表声明几张磁贴，页面就有几张")
                .hasSize(AssetFamilyRegistry.tiles().size());
    }
}
