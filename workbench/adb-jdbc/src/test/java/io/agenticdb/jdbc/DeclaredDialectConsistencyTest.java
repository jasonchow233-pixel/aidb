package io.agenticdb.jdbc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ A25 门禁：**声明层与运行时方言的一致性**。
 *
 * <h2>这个不一致是怎么来的</h2>
 * {@code declarations/connections/dameng.yaml} 声明了达梦，
 * 而运行时的 {@link DialectKind} 里**没有**达梦 ——
 * 于是「声明层支持、运行时不支持」，用户读文档会以为支持。
 *
 * <h2>为什么用「显式白名单」而不是删掉声明</h2>
 * 达梦是国内数据库的重要一环，删掉声明是<b>不可逆</b>的（将来要加回来得重写）。
 * 所以这里把它**显式登记**：白名单里的每个 id 都必须写清「为什么还没有运行时支持」。
 * 将来真做了达梦，把 id 从白名单挪到运行时即可 —— 白名单只允许变短。
 */
class DeclaredDialectConsistencyTest {

    /**
     * ⭐ 声明超前于运行时的连接 id。
     *
     * <p>每个条目都要写清「为什么运行时还没有」——
     * 这行注释是这个门禁的全部意义。
     */
    private static final Set<String> DECLARED_BUT_NOT_IMPLEMENTED = Set.of(
            // 达梦：声明已就绪（连接声明 + 方言词表），
            // 但运行时的 DialectKind / Dialect 还没做（要连真库才能验语义）。
            // 「二选一」里的另一条路（删声明）被否决 —— 不可逆，且达梦大概率要支持。
            "dameng");

    @Test
    void everyDeclaredConnectionIsEitherImplementedOrExplicitlyListed() throws Exception {
        Set<String> declared = declaredConnectionIds();
        Set<String> implemented = new LinkedHashSet<>();
        for (DialectKind kind : DialectKind.values()) {
            implemented.add(kind.id());
        }

        List<String> unaccounted = new ArrayList<>();
        for (String id : declared) {
            if (!implemented.contains(id) && !DECLARED_BUT_NOT_IMPLEMENTED.contains(id)) {
                unaccounted.add(id);
            }
        }

        assertThat(unaccounted)
                .as("这些连接**声明了但运行时没有**，而且没被登记进白名单 ——\n"
                        + "  · 要么给运行时加实现（写一个 Dialect + 在 DialectKind 登记），\n"
                        + "  · 要么把它加进 DECLARED_BUT_NOT_IMPLEMENTED 并写明原因，\n"
                        + "  · 要么删掉声明。\n"
                        + "  未登记：%s", String.join("\n  ", unaccounted))
                .isEmpty();
    }

    @Test
    void theWhitelistIsNotStale() {
        // 白名单里不该有「其实已经实现了」的条目 —— 那会让不一致重新变成隐形的。
        Set<String> implemented = new LinkedHashSet<>();
        for (DialectKind kind : DialectKind.values()) {
            implemented.add(kind.id());
        }
        assertThat(DECLARED_BUT_NOT_IMPLEMENTED)
                .as("白名单里的连接已经实现了 → 请从白名单里删掉")
                .doesNotContainAnyElementsOf(implemented);
    }

    @Test
    void everyRuntimeDialectIsDeclared() throws Exception {
        // 反向：运行时有的方言，声明层也得有（否则「加了运行时却忘了写声明」）。
        Set<String> declared = declaredConnectionIds();
        for (DialectKind kind : DialectKind.values()) {
            assertThat(declared)
                    .as("运行时方言 %s 在声明层也要有对应的连接声明", kind.id())
                    .contains(kind.id());
        }
    }

    /** 读声明层的连接 id（走声明层自己的加载器 —— 资源在 jar 里也能读）。 */
    private static Set<String> declaredConnectionIds() throws Exception {
        io.agenticdb.declarations.DeclaredModel model =
                io.agenticdb.declarations.DeclarationLoader.loadFromClasspath();
        Set<String> ids = new LinkedHashSet<>();
        for (var connection : model.connections()) {
            ids.add(connection.id());
        }
        assertThat(ids).as("读到的声明 id").isNotEmpty();
        return ids;
    }
}
