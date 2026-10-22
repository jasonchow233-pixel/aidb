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

    @Test
    void snippetsAndQueriesAreDistinguishable() {
        // ⭐ Q6：片段与查询的**用法不同**（插入 vs 执行）——
        //    混在一个列表里用户永远不知道自己点开的是哪一种。
        SettingsStore store = new SettingsStore();
        {
            SavedQueries saved = new SavedQueries(store, "query.saved.snippets");
            saved.save("月度报表", "SELECT * FROM orders");
            saved.saveSnippet("去重子句", "SELECT DISTINCT user_id FROM orders");

            assertThat(saved.all()).hasSize(2);
            assertThat(saved.snippets())
                    .as("只有片段进 snippets()")
                    .hasSize(1)
                    .first()
                    .satisfies(entry -> {
                        assertThat(entry.name()).isEqualTo("去重子句");
                        assertThat(entry.snippet()).isTrue();
                        assertThat(entry.kind().label()).isEqualTo("片段");
                    });
            assertThat(saved.all().stream().filter(e -> !e.snippet()).count())
                    .as("查询条目不是片段")
                    .isEqualTo(1);
        }
    }

    @Test
    void savingASnippetWithTheSameNameReplacesTheOldOne() {
        SettingsStore store = new SettingsStore();
        {
            SavedQueries saved = new SavedQueries(store, "query.saved.snippet.replace");
            saved.saveSnippet("片段", "SELECT 1");
            saved.saveSnippet("片段", "SELECT 2");

            assertThat(saved.snippets()).hasSize(1);
            assertThat(saved.snippets().getFirst().sql()).isEqualTo("SELECT 2");
        }
    }
}
