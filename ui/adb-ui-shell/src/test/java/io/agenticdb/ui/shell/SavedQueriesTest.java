package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 已保存查询库的门禁。
 *
 * <p>侧栏的「已保存查询」以前是空壳页 —— 按项目规则「占位要么接要么删」，
 * 现在接了真功能：命名、保存、重命名、删除、按名覆盖。
 */
class SavedQueriesTest {

    /** 每个用例一个独立的存储键 —— 免得互相污染。 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private static SavedQueries fresh() {
        return new SavedQueries(new SettingsStore(),
                "query.saved.test." + SEQ.incrementAndGet());
    }

    @Test
    void savesAndReadsBack() {
        SettingsStore settings = new SettingsStore();
        SavedQueries saved = new SavedQueries(settings, "query.saved.test." + SEQ.incrementAndGet());

        saved.save("月度报表", "SELECT count(*) FROM orders");
        saved.save("活跃用户", "SELECT * FROM users WHERE active = 1");

        List<SavedQueries.Entry> all = saved.all();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(SavedQueries.Entry::name)
                .containsExactlyInAnyOrder("月度报表", "活跃用户");
        assertThat(all).extracting(SavedQueries.Entry::sql)
                .anyMatch(sql -> sql.contains("FROM orders"));
    }

    @Test
    void sameNameOverwritesInsteadOfDuplicating() {
        SavedQueries saved = fresh();

        saved.save("报表", "SELECT 1");
        saved.save("报表", "SELECT 2");

        assertThat(saved.all()).hasSize(1);
        assertThat(saved.all().getFirst().sql()).isEqualTo("SELECT 2");
    }

    @Test
    void renameAndDelete() {
        SavedQueries saved = fresh();
        saved.save("旧名", "SELECT 1");
        saved.save("另一个", "SELECT 2");

        saved.rename("旧名", "新名");
        assertThat(saved.all()).extracting(SavedQueries.Entry::name)
                .containsExactlyInAnyOrder("新名", "另一个");

        saved.delete("新名");
        assertThat(saved.all()).extracting(SavedQueries.Entry::name)
                .containsExactly("另一个");
    }

    @Test
    void blankInputIsIgnored() {
        SavedQueries saved = fresh();
        saved.save("  ", "SELECT 1");
        saved.save("名字", "   ");
        assertThat(saved.all()).isEmpty();
    }

    @Test
    void survivesAcrossInstances() {
        SettingsStore settings = new SettingsStore();
        String key = "query.saved.test." + SEQ.incrementAndGet();
        new SavedQueries(settings, key).save("持久", "SELECT 1");

        // 新的实例从同一个 settings 里读回来 —— 说明真的落盘了。
        assertThat(new SavedQueries(settings, key).all())
                .extracting(SavedQueries.Entry::name)
                .contains("持久");
    }
}
