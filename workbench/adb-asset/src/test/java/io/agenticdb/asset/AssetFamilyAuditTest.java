package io.agenticdb.asset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资产族注册表的结构性门禁：加一种连接类型 = 在注册表登记一个族，别处不用改。
 *
 * <p>这个门禁守的是「单一真相源」：所有磁贴从 {@link AssetFamilyRegistry#tiles()} 出，
 * 不另开一份硬编码清单。若哪天有人把磁贴又写死在 UI 里，本测试（配合 UI 层的分支审计）
 * 会抓到「注册表里的资产种类与 UI 实际展示的不一致」。
 */
class AssetFamilyAuditTest {

    @AfterEach
    void tearDown() {
        AssetFamilyRegistry.reset();
    }

    @Test
    void everyAssetKindIsCoveredByExactlyOneFamilyOnceAllRegistered() {
        AssetFamilyRegistry.register(SqlFamily.INSTANCE);
        AssetFamilyRegistry.register(RedisFamily.INSTANCE);
        AssetFamilyRegistry.register(ObjectStoreFamily.INSTANCE);

        for (AssetKind kind : AssetKind.values()) {
            AssetFamily family = AssetFamilyRegistry.forKind(kind);
            assertThat(family)
                    .as("%s 必须被某个资产族登记，否则向导/树取不到它", kind)
                    .isNotNull();
        }
    }

    @Test
    void sqlFamilyCoversAllSixSqlKindsThroughTilesOnly() {
        AssetFamilyRegistry.register(SqlFamily.INSTANCE);
        assertThat(SqlFamily.INSTANCE.kinds()).containsExactlyInAnyOrder(
                AssetKind.SQLITE, AssetKind.POSTGRESQL, AssetKind.MYSQL,
                AssetKind.MARIADB, AssetKind.SQLSERVER, AssetKind.ORACLE);
        // 6 张磁贴，且每张的 kind 都在 kinds() 里 —— 没有「登记了类型却没磁贴」。
        assertThat(SqlFamily.INSTANCE.tiles()).hasSize(6);
        for (TileDescriptor tile : SqlFamily.INSTANCE.tiles()) {
            assertThat(SqlFamily.INSTANCE.kinds()).contains(tile.kind());
        }
    }

    @Test
    void registryTilesAggregateAllFamiliesInOrder() {
        AssetFamilyRegistry.register(SqlFamily.INSTANCE);
        AssetFamilyRegistry.register(RedisFamily.INSTANCE);
        AssetFamilyRegistry.register(ObjectStoreFamily.INSTANCE);

        // 6 (SQL) + 1 (Redis) + 4 (对象存储) = 11
        assertThat(AssetFamilyRegistry.tiles()).hasSize(11);
        assertThat(AssetFamilyRegistry.tiles().stream()
                .filter(t -> t.kind() == AssetKind.REDIS)).hasSize(1);
        assertThat(AssetFamilyRegistry.tiles().stream()
                .filter(t -> t.kind() == AssetKind.OBJECT_STORE)).hasSize(4);
    }

    @Test
    void objectStoreTilesEachCarryADistinctProvider() {
        AssetFamilyRegistry.register(ObjectStoreFamily.INSTANCE);
        assertThat(ObjectStoreFamily.INSTANCE.tiles()).hasSize(4);
        for (TileDescriptor tile : ObjectStoreFamily.INSTANCE.tiles()) {
            assertThat(tile.provider()).isNotNull();
            assertThat(tile.kind()).isEqualTo(AssetKind.OBJECT_STORE);
        }
    }

    @Test
    void nonConnectableFamiliesSignalWorkInProgressWithoutFakeControls() {
        AssetFamilyRegistry.register(RedisFamily.INSTANCE);
        AssetFamilyRegistry.register(ObjectStoreFamily.INSTANCE);
        // P1 Redis 与 P2 对象存储的向导页都已落地 -> 三个族全部可连。
        assertThat(RedisFamily.INSTANCE.connectable()).isTrue();
        assertThat(ObjectStoreFamily.INSTANCE.connectable()).isTrue();
        assertThat(SqlFamily.INSTANCE.connectable()).isTrue();
    }

    @Test
    void objectStoreFamilyDeclaresItsWizardStepInOnePlace() {
        assertThat(ObjectStoreFamily.INSTANCE.wizardStepIds())
                .containsExactly("objectstore");
        // 「可连」由步骤清单推导，而不是再写一个布尔字段 —— 两处不同步就不会发生。
        assertThat(ObjectStoreFamily.INSTANCE.connectable())
                .isEqualTo(!ObjectStoreFamily.INSTANCE.wizardStepIds().isEmpty());
    }

    @Test
    void everyFamilyIsRegisteredByDefaultWithoutAppShellStartup() {
        // 静态块自登记三族：ConnectionCatalog 在类初始化时就读 tiles()，不能等 AppShell。
        // reset() 后显式重新走同一条登记路径，避免依赖测试执行顺序。
        AssetFamilyRegistry.reset();
        AssetFamilyRegistry.registerDefaults();
        for (AssetKind kind : AssetKind.values()) {
            assertThat(AssetFamilyRegistry.forKind(kind))
                    .as("%s 必须在默认登记里就能找到族", kind)
                    .isNotNull();
        }
        assertThat(AssetFamilyRegistry.tiles()).hasSize(11);
    }
}
