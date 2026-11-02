package io.agenticdb.jdbc;

/**
 * ⭐ R42 N28-a：「改一个已经存在的视图」在这方言里怎么写。
 *
 * <p>视图没有 {@code ALTER VIEW … 换查询体} 这种通用写法（SQL Server 有
 * {@code ALTER VIEW}，MySQL 的 {@code ALTER VIEW} 只改属性不改查询体），
 * 所以重建方式必须按方言给，不能由 UI 拼字符串猜。
 *
 * <p>三种形态都有真库依据（R52 在五引擎容器上逐个跑过，见契约八）：
 * <ul>
 *   <li>{@link #OR_REPLACE} —— MySQL 8.4、MariaDB 11.8、PostgreSQL 16、Oracle 23ai
 *       都认 {@code CREATE OR REPLACE VIEW}；</li>
 *   <li>{@link #OR_ALTER} —— SQL Server 2016 SP1 起才有的
 *       {@code CREATE OR ALTER VIEW}；</li>
 *   <li>{@link #DROP_THEN_CREATE} —— 只有 <b>SQLite</b> 真的没有单句形式，
 *       只能先 {@code DROP VIEW} 再 {@code CREATE VIEW}。</li>
 * </ul>
 *
 * <p>⚠️ <b>D-29 的翻案记录</b>：这里原来写的是「MySQL 8.0 没有
 * {@code CREATE OR REPLACE VIEW}，那是 MariaDB 的分支特性」。说法来自旧文档传承，
 * 一直没在真库上验过；R52 建视图契约一跑就翻 —— MySQL 8.4 收下单句、
 * {@code SHOW CREATE VIEW} 回读的是新正文。判反的代价不是「多打一句 SQL」：
 * 两句之间视图<b>真的不存在</b>，依赖它的对象会失败，界面还要为此多要一道
 * 危险确认（{@code StructurePlan#approvalRequired()}）。
 *
 * <p>为什么默认值是 {@link #DROP_THEN_CREATE}：它是六种方言里唯一都认的写法，
 * 新方言不 override 也不会生成一句「看着对、执行必报错」的 SQL。
 */
public enum ViewReplace {

    /** {@code CREATE OR REPLACE VIEW …} —— 一条语句完成重建。 */
    OR_REPLACE,

    /** {@code CREATE OR ALTER VIEW …} —— SQL Server 的写法。 */
    OR_ALTER,

    /** 没有单句形式：先 {@code DROP VIEW} 再 {@code CREATE VIEW}。 */
    DROP_THEN_CREATE
}
